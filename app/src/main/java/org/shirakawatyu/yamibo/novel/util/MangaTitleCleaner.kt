package org.shirakawatyu.yamibo.novel.util

class MangaTitleCleaner {
    companion object {

        /**
         * 提取纯净书名 (用于后续去重和搜索)
         */
        fun getCleanBookName(rawTitle: String): String {
            var clean = rawTitle

            // 1. 无差别干掉所有的 【汉化组】、[作者名]、[长篇连载]、[阅读权限 20] 等外围标签
            clean = clean.replace(Regex("【.*?】|\\[.*?\\]"), "")

            // 干掉类似 (C107) 或 （C106）这种展会标签
            clean = clean.replace(Regex("\\([Cc]\\d+\\)|（[Cc]\\d+）"), "")

            // 2. 截断章节标记及其后面的所有内容 (如 "第8.5话 从家庭餐厅回家后" -> 切掉)
            // 匹配："第x话"、" 番外"、" 特典"、" 附录"、" 前篇" 等
            val chapterMarkerPattern =
                Regex("(第\\s*[\\d\\.\\-]+\\s*[话話]|\\s+番外|\\s+特典|\\s+附录|\\s+(前篇|中篇|后篇))")
            val markerMatch = chapterMarkerPattern.find(clean)
            if (markerMatch != null) {
                clean = clean.substring(0, markerMatch.range.first)
            }

            // 3. 处理那种没有 "第x话"，只有末尾跟着数字的情况 (如 " 22.3", " 09", ")50")
            // 匹配：字符串末尾的数字 (允许前面有空格或右括号)
            clean = clean.replace(Regex("[\\)\\s]+[\\d\\.]+\\s*$"), "")

            // 4. 清洗两端残留的无用符号 (如连字符、斜杠、多余的空格)
            clean = clean.replace(Regex("^[\\s\\-|/\\)]+|[\\s\\-|/\\(]+$"), "")

            return clean.trim()
        }

        /**
         * 提取话数 (用于排序，必须返回 Float)
         */
        fun extractChapterNum(rawTitle: String): Float {
            // 先去掉各种括号，防止把 [阅读权限 20] 当成第20话
            val cleanTitle = rawTitle.replace(Regex("【.*?】|\\[.*?\\]|\\(.*?\\)|（.*?）"), "")

            // 规则 1: (第)?X话其Y (e.g., "1话其2" -> 1.2)
            val matchQi = Regex("(?:第)?\\s*(\\d+)\\s*[话話]\\s*其\\s*(\\d+)").find(cleanTitle)
            if (matchQi != null) {
                return "${matchQi.groupValues[1]}.${matchQi.groupValues[2]}".toFloatOrNull() ?: 0f
            }

            // 规则 2: 第X-Y话 (e.g., "第9-2话" -> 9.2)
            val matchDash = Regex("第\\s*(\\d+)[\\-](\\d+)\\s*[话話]").find(cleanTitle)
            if (matchDash != null) {
                return "${matchDash.groupValues[1]}.${matchDash.groupValues[2]}".toFloatOrNull()
                    ?: 0f
            }

            // 规则 3: 第X话 / 第X.Y话 (e.g., "第8.5话", "第05话" -> 8.5, 5.0)
            val matchStandard = Regex("第\\s*([\\d\\.]+)\\s*[话話]").find(cleanTitle)
            if (matchStandard != null) {
                return matchStandard.groupValues[1].toFloatOrNull() ?: 0f
            }

            // ================== 新增：特殊章节规则 ==================
            // 规则 4: 番外 / 特典 / 附录 (赋予极大的基础值，让它们排在正篇后面)
            // 匹配 "番外"、"番外篇"、"番外01" 等
            val matchExtra = Regex("(番外|特典|附录)(?:篇)?\\s*([\\d\\.]*)").find(cleanTitle)
            if (matchExtra != null) {
                val type = matchExtra.groupValues[1]
                val numStr = matchExtra.groupValues[2]
                val num = numStr.toFloatOrNull() ?: 0f

                val base = when (type) {
                    "番外" -> 1000f
                    "特典" -> 2000f
                    "附录" -> 3000f
                    else -> 4000f
                }
                return base + num
            }

            // 规则 5: 前/中/后篇 赋予微小小数以便在同一话内排序 (如：前篇 0.1, 后篇 0.3)
            if (cleanTitle.contains("前篇") || cleanTitle.contains("上")) return 0.1f
            if (cleanTitle.contains("中篇") || cleanTitle.contains("中")) return 0.2f
            if (cleanTitle.contains("后篇") || cleanTitle.contains("下")) return 0.3f

            // 规则 6: 啥都没写，只在最后丢个数字 (e.g., "22.3", ")50", " 09")
            val matchLastNum = Regex("([\\d\\.]+)(?!.*\\d)").find(cleanTitle)
            if (matchLastNum != null && matchLastNum.groupValues[1] != ".") {
                return matchLastNum.groupValues[1].toFloatOrNull() ?: 0f
            }

            // 默认返回 0f (完全没进度的短篇集)
            return 0f
        }

        /**
         * 从 URL 提取 tid (用于去重唯一键)
         */
        fun extractTidFromUrl(url: String): String? {
            val match = Regex("tid=(\\d+)").find(url) ?: Regex("thread-(\\d+)-").find(url)
            return match?.groupValues?.get(1)
        }
    }
}