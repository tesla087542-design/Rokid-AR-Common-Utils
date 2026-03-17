package com.museum.guide.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "SherpaAsrEngine"

/**
 * 基于 Sherpa-ONNX KWS 的离线关键词识别引擎。
 * 实现 [AsrEngine] 接口，通过 [onKeywordDetected] 回调向 InteractionMgr 上报结果。
 *
 * 关键词映射（对照 0308交互表）：
 *   识别/看    → RECOGNIZE   配网       → WIFI
 *   退出/关闭  → EXIT        语音/讲解  → NARRATE
 *   下一页     → NEXT_PAGE   上一页     → PREV_PAGE
 *   暂停/继续  → PAUSE_RESUME 提问/对话 → CHAT
 *
 * 模型文件（需放入 assets/commands/）：
 *   encoder.onnx / decoder.onnx / joiner.onnx / tokens.txt / keywords.txt
 *
 * 状态联动：
 *   - onEnterState(Recognizing) → InteractionMgr.stopAsr()  → stop()
 *   - onExitState(Recognizing)  → InteractionMgr.startAsr() → start()
 */
class SherpaAsrEngine(private val context: Context) : AsrEngine {

    override var onKeywordDetected: ((keyword: String) -> Unit)? = null
    override var onUtterance: ((text: String) -> Unit)? = null       // KWS 模式下不使用
    override var onPartialUtterance: ((text: String) -> Unit)? = null // KWS 模式下不使用

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var job: Job? = null

    private val sampleRate    = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat   = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize    = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(1280)   // 保底 80ms 帧（640 samples × 2 bytes）

    init {
        CoroutineScope(Dispatchers.IO).launch { initEngine() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 引擎初始化
    // ─────────────────────────────────────────────────────────────────────────

    private fun initEngine() {
        try {
            // native 层不能直接读 assets，需复制到内部存储
            val modelDir = context.filesDir.absolutePath + "/commands"
            copyAssetsToFiles("commands", modelDir)

            val transducer = OnlineTransducerModelConfig.builder()
                .setEncoder("$modelDir/encoder.onnx")
                .setDecoder("$modelDir/decoder.onnx")
                .setJoiner("$modelDir/joiner.onnx")
                .build()
            val modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens("$modelDir/tokens.txt")
                .setNumThreads(1)
                .build()
            val config = KeywordSpotterConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setKeywordsFile("$modelDir/keywords.txt")
                .build()
            spotter = KeywordSpotter(config)
            stream  = spotter!!.createStream()
            Log.i(TAG, "KeywordSpotter initialized")
            // 若 start() 在引擎就绪前被调用（被跳过），初始化完成后自动补启动
            if (!isRunning && onKeywordDetected != null) {
                Log.i(TAG, "Auto-starting ASR after delayed init")
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine init failed: ${e.message}")
        }
    }

    /** 将 assets/srcDir 下所有文件复制到 destDir（每次强制覆盖，确保更新生效） */
    private fun copyAssetsToFiles(srcDir: String, destDir: String) {
        val destFile = java.io.File(destDir)
        if (!destFile.exists()) destFile.mkdirs()
        context.assets.list(srcDir)?.forEach { name ->
            val dest = java.io.File(destDir, name)
            // 强制覆盖：避免旧版本 keywords.txt 残留导致 "Encode keywords failed"
            context.assets.open("$srcDir/$name").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AsrEngine 接口实现
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override fun start() {
        if (isRunning) return
        val kws = spotter ?: run { Log.w(TAG, "start() skipped: spotter not ready"); return }

        // 运行时检查麦克风权限，避免 AudioRecord.startRecording() native crash
        if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO)) {
            Log.w(TAG, "start() skipped: RECORD_AUDIO permission not granted")
            return
        }

        isRunning = true

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${rec.state})")
            rec.release()
            isRunning = false
            return
        }
        rec.startRecording()
        audioRecord = rec

        job = CoroutineScope(Dispatchers.IO).launch {
            val shortBuf = ShortArray(bufferSize / 2)   // bufferSize 是字节数，Short = 2 bytes
            while (isRunning) {
                val readCount = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: 0
                if (readCount <= 0) continue

                // Short PCM [-32768, 32767] → Float [-1, 1]
                val samples = FloatArray(readCount) { shortBuf[it] / 32768.0f }
                stream?.acceptWaveform(samples, sampleRate)
                kws.decode(stream!!)

                if (kws.isReady(stream!!)) {
                    val result = kws.getResult(stream!!)
                    val keyword = result?.getKeyword() ?: ""
                    if (keyword.isNotEmpty()) {
                        Log.d(TAG, "Detected: \"$keyword\"")
                        // 重置 stream 以避免重复触发
                        stream?.release()
                        stream = kws.createStream("")
                        mapKeyword(keyword)?.let { onKeywordDetected?.invoke(it) }
                    }
                }
            }
        }
        Log.d(TAG, "ASR started")
    }

    override fun stop() {
        isRunning = false
        job?.cancel(); job = null
        audioRecord?.stop()
        audioRecord?.release(); audioRecord = null
        Log.d(TAG, "ASR stopped")
    }

    override fun release() {
        stop()
        stream?.release(); stream = null
        spotter?.release(); spotter = null
        onKeywordDetected = null
        Log.d(TAG, "ASR released")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 关键词映射（对照 0308交互表全量14条）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 将 KWS 检测到的原始关键词字符串映射为标准关键词字符串，
     * 供 InteractionMgr 的 ASR_KEYWORD_MAP 查表使用。
     *
     * 返回 null 表示该词未纳入指令表，InteractionMgr 会打印 warn 并忽略。
     */
    private fun mapKeyword(raw: String): String? = when {
        // RECOGNIZE
        raw.contains("识别") || raw.contains("看一看") || raw.contains("这是什么") -> "识别展品"
        // WIFI
        raw.contains("配网")                                                      -> "配网"
        // EXIT
        raw.contains("退出") || raw.contains("关闭")                              -> "退出"
        // NARRATE
        raw.contains("语音") || raw.contains("讲解") || raw.contains("说话")      -> "开始讲解"
        // PAUSE_RESUME
        raw.contains("暂停")                                                      -> "暂停"
        raw.contains("继续")                                                      -> "继续"
        // PAGE
        raw.contains("下一页")                                                    -> "下一页"
        raw.contains("上一页")                                                    -> "上一页"
        // CHAT
        raw.contains("提问") || raw.contains("对话")                              -> "提问"
        else -> null
    }
}
