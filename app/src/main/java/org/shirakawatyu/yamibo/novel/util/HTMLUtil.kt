package org.shirakawatyu.yamibo.novel.util

/**
 * HTML文本处理工具
 */
class HTMLUtil {
    companion object {
        fun toText(html: String): String {
            val text = html
                // 1. 将 <br> 标签替换为单个换行符
                .replace(Regex("(?i)<br\\s*/?>"), "\n\n")

                // 2. 将 </p> 和 </div> (块级元素结束标签) 替换为两个换行符
                .replace(Regex("(?i)</(p|div)>"), "\n\n")

                // 3. 移除所有其他HTML标签 (例如 <div...>, <font...>, <i>)
                .replace(Regex("<[^>]+>"), "")

                // 4. 解码 &nbsp;
                .replace("&nbsp;", " ")

                // 5. 处理缩进规则
                .replace(Regex(" {3,}"), "    ")

                // 6. 清理多余的换行符。
                .replace(Regex("\n{3,}"), "\n\n")

            // 7. 移除开头和结尾的空白
            return text.trim()
        }
    }
}