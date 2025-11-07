package org.shirakawatyu.yamibo.novel.util

class HTMLUtil {
    companion object {
        fun toText(html: String): String {
            return html
                .replace("<br>", "\n")
                .replace(Regex("<.*?>"), "")
//                .replace(" ", "")
                .replace("&nbsp;", " ")
//                .replace("\u0020\u0020\u0020", "\u3000")
                .replace(Regex(" {3,}"), "    ")
                .replace(Regex("\n+"), "\n")
                .trim()
        }
    }
}