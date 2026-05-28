package org.shirakawatyu.yamibo.novel.util.reader

/**
 * HTML文本处理工具。
 *
 * */
class HTMLUtil {
    companion object {
        private val BR_REGEX = Regex("(?i)<br\\s*/?>")
        private val BLOCK_END_REGEX = Regex("(?i)</(p|div)>")
        private val TAG_REGEX = Regex("<[^>]+>")
        private val ATTACH_REGEX = Regex("\\[attach]\\d+\\[/attach]")
        private val MULTI_SPACE_REGEX = Regex(" {3,}")
        private val MULTI_NEWLINE_REGEX = Regex("\n{3,}")

        fun toText(html: String): String {
            val text = html
                // 0. 清除零宽字符等不可见格式符，防止干扰排版
                .replace("\u200B", "")

                // 1. 将 <br> 标签替换为两个换行符，保持原行为
                .replace(BR_REGEX, "\n\n")

                // 2. 将 </p> 和 </div> 块级元素结束标签替换为两个换行符
                .replace(BLOCK_END_REGEX, "\n\n")

                // 3. 移除所有其他 HTML 标签，例如 <div...>, <font...>, <i>
                .replace(TAG_REGEX, "")

                // 4. 移除 BBCode 标签，例如 [attach]123456[/attach]
                .replace(ATTACH_REGEX, "")

                // 5. 解码 &nbsp;
                .replace("&nbsp;", " ")

                // 6. 处理缩进规则
                .replace(MULTI_SPACE_REGEX, "    ")

                // 7. 清理多余换行符
                .replace(MULTI_NEWLINE_REGEX, "\n\n")

            // 8. 移除开头和结尾空白
            return text.trim()
        }
    }
}
