package com.museum.guide.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView

/**
 * 文字高亮渲染工具。
 * 将 [text] 中 [0, highlightEnd) 范围标记为金黄色 #FFD700，其余为白色。
 *
 * 由 [ARContentView.onTtsProgress] 内部调用；
 * 也可独立用于任意 TextView（对话气泡等场景）。
 */
object HighlightingRenderer {

    private const val COLOR_HIGHLIGHT = 0xFFFFD700.toInt()
    private const val COLOR_NORMAL    = 0xFFFFFFFF.toInt()

    /**
     * 渲染高亮文本到 [textView]。
     * @param text         完整文本
     * @param highlightEnd 已读字符数（0 = 无高亮，text.length = 全部高亮）
     */
    fun render(textView: TextView, text: String, highlightEnd: Int) {
        val ssb = SpannableStringBuilder(text)
        val end = highlightEnd.coerceIn(0, text.length)
        if (end > 0) {
            ssb.setSpan(
                ForegroundColorSpan(COLOR_HIGHLIGHT),
                0, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (end < text.length) {
            ssb.setSpan(
                ForegroundColorSpan(COLOR_NORMAL),
                end, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        textView.text = ssb
    }
}
