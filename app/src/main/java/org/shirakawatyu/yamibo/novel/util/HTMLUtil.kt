package org.shirakawatyu.yamibo.novel.util

class HTMLUtil {
    companion object {
        fun toText(html: String): String {
            val text = html
                // 1. 将 <br> 标签替换为单个换行符
                .replace(Regex("(?i)<br\\s*/?>"), "\n")

                // 2. 将 </p> 和 </div> (块级元素结束标签) 替换为两个换行符
                //    这是产生段落分隔的关键
                .replace(Regex("(?i)</(p|div)>"), "\n\n")

                // 3. 移除所有其他HTML标签 (例如 <div...>, <font...>, <i>)
                .replace(Regex("<[^>]+>"), "")

                // 4. 解码 &nbsp;
                .replace("&nbsp;", " ")

                // 5. 处理你的缩进规则
                .replace(Regex(" {3,}"), "    ")

                // 6. [重要] 清理多余的换行符。
                //    将三个或更多(可能包含空格)的连续换行符，压缩为两个换行符。
                //    这能修复 "</div><br>" (变成 "\n\n\n") 导致的 "多余空行" 问题。
                .replace(Regex("\n{3,}"), "\n\n")

            // 7. 移除开头和结尾的空白
            return text.trim()
        }
    }
}