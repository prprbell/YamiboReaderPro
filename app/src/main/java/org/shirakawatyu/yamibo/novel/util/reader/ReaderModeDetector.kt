package org.shirakawatyu.yamibo.novel.util.reader

/**
 * 阅读模式检测工具类
 * 用于判断当前URL是否为可转换为阅读模式的帖子页面
 */
class ReaderModeDetector {
    companion object {
        private val textSections = listOf("文學區", "文学区", "轻小说/译文区", "TXT小说区")

        /**
         * 判断URL是否为帖子页面（可转换为阅读模式）
         * @param url 当前页面URL
         * @return 是否可以转换为阅读模式
         */
        fun canConvertToReaderMode(url: String?, title: String? = null): Boolean {
            if (url.isNullOrBlank()) return false

            // 1. 必须是帖子查看页面
            val isThreadPage = url.contains("mod=viewthread") && url.contains("tid=")
            if (!isThreadPage) return false

            // 2. 标题为空时直接不显示
            if (title.isNullOrBlank()) return false

            // 3. 只有标题里确切包含这三个板块名之一，才允许显示阅读按钮
            return textSections.any { title.contains(it) }
        }

        /**
         * 从完整URL中提取可用于导航到ReaderPage的路径
         * 例如: https://bbs.yamibo.com/forum.php?mod=viewthread&tid=563621&extra=page%3D1&mobile=2
         * 提取为: forum.php?mod=viewthread&tid=563621&extra=page%3D1&mobile=2
         *
         * @param fullUrl 完整的URL
         * @return 提取的路径，如果无法提取则返回null
         */
        fun extractThreadPath(fullUrl: String?): String? {
            if (fullUrl.isNullOrBlank()) return null

            return try {
                // 移除域名部分，保留路径
                val baseUrl = "https://bbs.yamibo.com/"
                if (fullUrl.startsWith(baseUrl)) {
                    fullUrl.removePrefix(baseUrl)
                } else if (fullUrl.contains("forum.php")) {
                    // 如果URL格式不同，尝试从forum.php开始提取
                    fullUrl.substring(fullUrl.indexOf("forum.php"))
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}