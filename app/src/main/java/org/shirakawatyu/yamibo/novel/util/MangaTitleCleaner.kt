package org.shirakawatyu.yamibo.novel.util

class MangaTitleCleaner {
    companion object {
        fun getCleanThreadTitle(rawTitle: String): String {
            return rawTitle.replace(
                Regex("(?i)\\s+[-—–_]+\\s+(.*?[区板]\\s+[-—–_]+\\s+)?(百合会|论坛|手机版|Powered by).*$"),
                ""
            ).trim()
        }

        /**
         * 提取纯净书名 (用于后续去重和搜索)
         */
        fun getCleanBookName(rawTitle: String): String {
            var clean = getCleanThreadTitle(rawTitle)

            clean = clean.replace(Regex("\\s+-\\s+.*?(中文百合漫画区|百合会|论坛).*$"), "")
            clean = clean.replace(Regex("【.*?】|\\[.*?\\]"), "")
            clean = clean.replace(Regex("\\([Cc]\\d+\\)|（[Cc]\\d+）"), "")
            clean = clean.replace(Regex("\\s*[|｜].*$"), "")

            // 3. 截断章节标记及其后面的所有内容
            val chapterMarkerPattern = Regex(
                "(?i)(" + // (?i) 表示忽略大小写
                        "第\\s*[\\d\\.\\-零一二两三四五六七八九十百千]+\\s*[话話回卷季]|" +
                        "[-—\\s]*[#＃]\\s*\\d+|" +
                        "[-—\\s]*S\\d+(\\s*EP\\d+)?|" +
                        "[-—\\s]*EP\\d+|" +
                        "[-—\\s]*Vol\\.?\\s*\\d+|" +
                        "[-—\\s]*Ch\\.?\\s*\\d+|" +
                        "[-—\\s]*(番外|特典|附录|短篇|单行本|最终话|最終話|最终回|最終回|大结局)|" +
                        "[-—\\s]+(前篇|中篇|后篇|上|中|下)|" +
                        "[-—\\s]*[(（]\\s*[\\d\\.\\-零一二两三四五六七八九十百千]+\\s*[)）]|" +
                        "\\s+\\d+(?:\\.\\d+)?\\s+" +
                        ")"
            )
            val markerMatch = chapterMarkerPattern.find(clean)
            if (markerMatch != null) {
                clean = clean.substring(0, markerMatch.range.first)
            }

            clean = clean.replace(Regex("\\s*\\d+(\\.\\d+)?\\s*$"), "")
            clean = clean.replace(Regex("[！？\\?！!~。，、\\.]+$"), "")
            clean = clean.replace(Regex("^[\\s\\-|/\\)#]+|[\\s\\-|/\\(#:]+$"), "").trim()

            return clean
        }

        /**
         * 【新增的终极兜底策略】：提取核心搜索词
         * 哪怕展示的标题残留了脏数据，只要提取前几个核心字去搜，就能百发百中
         */
        fun getSearchKeyword(cleanName: String): String {
            // 兜底 1：遇到任何常见的“副标题分割符”或括号、空格，直接切断，只取第一块
            val chunks = cleanName.split(Regex("[-—\\s|｜(（:：！？\\?!]"))
            var keyword = chunks.firstOrNull { it.isNotBlank() } ?: cleanName
            // 兜底 2：如果切出来的第一块实在太短（比如只有一个字），用单字搜索会搜出几百个无关帖子
            if (keyword.length < 2) {
                keyword = if (cleanName.length > 4) cleanName.substring(0, 4) else cleanName
            }

            // 兜底 3：防超长Discuz报错
            if (keyword.length > 12) {
                keyword = keyword.substring(0, 12).trim()
            }
            keyword = keyword.replace(Regex("\\s*\\d+$"), "").trim()
            return keyword
        }

        /**
         * 提取话数 (用于排序，必须返回 Float)
         */
        fun extractChapterNum(rawTitle: String): Float {
            // 先去掉各种括号，防止把 [阅读权限 20] 当成第20话
            val cleanTitle = rawTitle
                .replace(Regex("【.*?】|\\[.*?\\]|\\(.*?\\)|（.*?）"), "")
                .replace(Regex("\\d+\\s*[xX×]\\s*\\d+"), "")
            if (Regex("番外|特典|附录|SP", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)) {
                return 0f
            }
            // ==========================================
            // 第一步：提取小数修饰符 (前中后/上下)
            // ==========================================
            var subModifier = 0f
            // (?!.*[\d零一二两三四五六七八九十百千]) 确保上/下后面没有主要数字，防止误伤 "上册第3话"
            val noDigitAfter = "(?!.*[\\d零一二两三四五六七八九十百千])"

            if (Regex("(前篇|上)$noDigitAfter").containsMatchIn(cleanTitle)) subModifier = 0.1f
            else if (Regex("(中篇|中)$noDigitAfter").containsMatchIn(cleanTitle)) subModifier = 0.2f
            else if (Regex("(后篇|下)$noDigitAfter").containsMatchIn(cleanTitle)) subModifier = 0.3f

            // ==========================================
            // 第二步：提取主话数
            // ==========================================
            var baseNum = -1f
            val circleMap = mapOf(
                '①' to 0.1f, '②' to 0.2f, '③' to 0.3f, '④' to 0.4f, '⑤' to 0.5f,
                '⑥' to 0.6f, '⑦' to 0.7f, '⑧' to 0.8f, '⑨' to 0.9f
            )
            val circleMatch = Regex("[①②③④⑤⑥⑦⑧⑨]").find(cleanTitle)
            if (circleMatch != null) {
                subModifier = circleMap[circleMatch.value[0]] ?: 0f
            }

            // 规则 1: (第)?X话其Y (e.g., "1话其2" -> 1.02f)
            val matchQi =
                Regex("(?:第)?\\s*([\\d\\.]+|[零一二两三四五六七八九十百千]+)\\s*[话話]\\s*其\\s*([\\d\\.]+|[零一二两三四五六七八九十百千]+)").find(
                    cleanTitle
                )
            if (matchQi != null) {
                baseNum =
                    parseNumber(matchQi.groupValues[1]) + (parseNumber(matchQi.groupValues[2]) / 100f)
            }

            // 规则 2: 第X-Y话 (e.g., "第9-2话" -> 9.02f)
            if (baseNum == -1f) {
                val matchDash =
                    Regex("第\\s*([\\d]+|[零一二两三四五六七八九十百千]+)[\\-]([\\d]+|[零一二两三四五六七八九十百千]+)\\s*[话話]").find(
                        cleanTitle
                    )
                if (matchDash != null) {
                    baseNum =
                        parseNumber(matchDash.groupValues[1]) + (parseNumber(matchDash.groupValues[2]) / 100f)
                }
            }

            // 规则 2.5: 匹配竖线或横杠后直接跟着的数字 (e.g., "| 33. 夏日飞行" -> 33)
            if (baseNum == -1f) {
                val matchSepNum =
                    Regex("[-—|｜]\\s*(\\d+(?:\\.\\d+)?)(?:\\s|\\.|$)").find(cleanTitle)
                if (matchSepNum != null) {
                    baseNum = matchSepNum.groupValues[1].toFloatOrNull() ?: 0f
                }
            }

            // 规则 3: 第X话 / 第X.Y话 (e.g., "第8.5话", "第八话" -> 8.5, 8.0)
            if (baseNum == -1f) {
                val matchStandard =
                    Regex("第\\s*([\\d\\.]+|[零一二两三四五六七八九十百千]+)\\s*[话話]").find(
                        cleanTitle
                    )
                if (matchStandard != null) {
                    baseNum = parseNumber(matchStandard.groupValues[1])
                }
            }


            // 规则 4.5: 最终话
            if (baseNum == -1f) {
                if (Regex("最终话|最終話|最终回|最終回|大结局").containsMatchIn(cleanTitle)) {
                    baseNum = 999f
                }
            }

            // 规则 6: 啥都没写，只在最后丢个数字
            if (baseNum == -1f) {
                val matchLastNum = Regex("([\\d\\.]+)(?!.*\\d)").find(cleanTitle)
                if (matchLastNum != null && matchLastNum.groupValues[1] != ".") {
                    baseNum = matchLastNum.groupValues[1].toFloatOrNull() ?: 0f
                }
            }

            // 兜底：如果一圈下来啥也没匹配到，主话数为 0
            if (baseNum == -1f) {
                baseNum = 0f
            }
            // ==========================================
            // 第三步：合并主副话数并严格限制浮点精度
            // ==========================================
            return Math.round((baseNum + subModifier) * 1000) / 1000f
        }

        /**
         * 从 URL 提取 tid (用于去重唯一键)
         */
        fun extractTidFromUrl(url: String): String? {
            val match = Regex("tid=(\\d+)").find(url) ?: Regex("thread-(\\d+)-").find(url)
            return match?.groupValues?.get(1)
        }

        /**
         * 将中文数字/阿拉伯数字统一解析为 Float 浮点数
         */
        private fun parseNumber(numStr: String): Float {
            // 如果本来就是阿拉伯数字，直接返回
            numStr.toFloatOrNull()?.let { return it }

            var total = 0f
            var number = -1f
            for (i in numStr.indices) {
                val c = numStr[i]
                val v = when (c) {
                    '零' -> 0f; '一' -> 1f; '二', '两' -> 2f; '三' -> 3f
                    '四' -> 4f; '五' -> 5f; '六' -> 6f; '七' -> 7f
                    '八' -> 8f; '九' -> 9f; else -> -1f
                }
                if (v != -1f) {
                    number = v
                } else {
                    val unit = when (c) {
                        '十' -> 10f; '百' -> 100f; '千' -> 1000f; else -> 0f
                    }
                    if (unit > 0) {
                        if (number == -1f) number = 1f // 处理如 "十一" 的情况 (隐去了一十)
                        total += number * unit
                        number = -1f
                    }
                }
            }
            if (number != -1f) {
                total += number
            }
            return total
        }
    }
}