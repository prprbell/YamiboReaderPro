package org.shirakawatyu.yamibo.novel.util.manga

/**
 * 漫画标题清洗工具
 */
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
            clean = clean.replace(Regex("(?i)[\\(（]?c\\d+[\\)）]?"), "")
            clean = clean.replace(Regex("\\s*[|｜].*$"), "")
            // 3. 截断章节标记及其后面的所有内容
            val chapterMarkerPattern = Regex(
                "(?i)(" +
                        "第\\s*[\\d\\.\\-零一二两三四五六七八九十百千]+|" +
                        "[-—\\s]*[#＃]\\s*\\d+|" +
                        "[-—\\s]*S\\d+(\\s*EP\\d+)?|" +
                        "[-—\\s]*EP\\d+|" +
                        "[-—\\s]*Vol\\.?\\s*\\d+|" +
                        "[-—\\s]*Ch\\.?\\s*\\d+|" +
                        "[-—\\s]*(番外|特典|卷后附|卷彩页|附录|短篇|单行本|最终话|最終話|最终回|最終回|大结局)|" +
                        "[-—\\s]+(前篇|中篇|后篇|上|中|下)|" +
                        "[-—\\s]*[(（]\\s*[\\d\\.\\-零一二两三四五六七八九十百千]+\\s*[)）]|" +
                        "\\s*(?<!\\d)\\d+(?:\\.\\d+)?\\s*(?:[话話织回章节幕折更])?\\s*(?=[：:—\\-「【\\[(（《]|\\s|$)" +
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
         * 独立提取作者名前缀
         */
        fun extractAuthorPrefix(rawTitle: String): String {
            val prefixMatch = Regex("^(?:【.*?】|\\[.*?\\]|[\\s\\u00A0\\u3000])+").find(rawTitle)

            if (prefixMatch != null) {
                val bracketMatch =
                    Regex("【(.*?)】|\\[(.*?)\\]").findAll(prefixMatch.value).lastOrNull()
                if (bracketMatch != null) {
                    return bracketMatch.groupValues[1].ifEmpty { bracketMatch.groupValues[2] }
                        .trim()
                }
            }
            return ""
        }

        /**
         * 提取核心搜索词
         */
        fun getSearchKeyword(rawTitle: String): String {
            val cleanName = getCleanBookName(rawTitle)
            val author = extractAuthorPrefix(rawTitle)

            val fullKeyword = if (author.isNotBlank()) {
                "$author $cleanName"
            } else {
                cleanName
            }
            if (fullKeyword.length > 18) {
                return fullKeyword.substring(0, 18).trim()
            }
            return fullKeyword
        }

        private const val NUM =
            "(\\d+(?:\\.\\d+)?|[０-９]+(?:\\.[０-９]+)?|[〇零一二两三四五六七八九十百千]+|[①-⑳]|[Ⅰ-Ⅻ])"

        private const val ARABIC = "(\\d+(?:\\.\\d+)?|[０-９]+(?:\\.[０-９]+)?)"

        fun extractChapterNum(rawTitle: String): Float {
            val cleanTitle = rawTitle
                .replace(Regex("【.*?】|\\[.*?\\]|\\(.*?\\)|（.*?）|「.*?」|《.*?》"), "")
                .replace(Regex("\\d+\\s*[xX×]\\s*\\d+"), "")
                .replace(Regex("(?i)\\bc\\d+\\b"), "")

            if (Regex(
                    "番外|特典|附录|SP|卷后附|卷彩页|小剧场|小漫画",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(rawTitle)
            ) {
                return 0f
            }
            if (Regex("最终话|最終話|最终回|最終回|大结局").containsMatchIn(cleanTitle)) {
                return 999f
            }

            // 3. 计算微调值 (前中后篇、①②③)
            var subModifier = 0f

            val modPrefix = "(?<=[\\s\\-—_/(（\\[【话話回章节幕折更\\d]|^)"
            val modSuffix = "(?=[\\s)）\\]】!！？?。，~]*$)"

            if (Regex("(?:前篇|${modPrefix}上)$modSuffix").containsMatchIn(cleanTitle)) subModifier =
                0.1f
            else if (Regex("(?:中篇|${modPrefix}中)$modSuffix").containsMatchIn(cleanTitle)) subModifier =
                0.2f
            else if (Regex("(?:后篇|${modPrefix}下)$modSuffix").containsMatchIn(cleanTitle)) subModifier =
                0.3f

            val circleMap = mapOf(
                '①' to 0.1f,
                '②' to 0.2f,
                '③' to 0.3f,
                '④' to 0.4f,
                '⑤' to 0.5f,
                '⑥' to 0.6f,
                '⑦' to 0.7f,
                '⑧' to 0.8f,
                '⑨' to 0.9f
            )
            Regex("[①②③④⑤⑥⑦⑧⑨]").find(cleanTitle)
                ?.let { subModifier = circleMap[it.value[0]] ?: 0f }

            val baseNum =
                // 规则 1.1: 明确带“话”等字眼的 其之 (e.g., "14话 其之2")
                Regex("(?:第)?\\s*$NUM\\s*[话話织回章节幕折更].*?其[之の]?\\s*$NUM").find(cleanTitle)
                    ?.let {
                        parseNumber(it.groupValues[1]) + (parseNumber(it.groupValues[2]) / 100f)
                    }
                // 规则 1.2: 没带话，但是靠得很近的其之 (限制中间长度，防止跨越太长匹配到“百合”)
                    ?: Regex("(?:第)?\\s*$NUM\\s*[^\\d零一二两三四五六七八九十百千]{0,5}?其[之の]?\\s*$NUM").find(
                        cleanTitle
                    )?.let {
                        parseNumber(it.groupValues[1]) + (parseNumber(it.groupValues[2]) / 100f)
                    }
                    // 规则 2: 第X-Y
                    ?: Regex("第\\s*$NUM\\s*[-—]\\s*$NUM").find(cleanTitle)?.let {
                        parseNumber(it.groupValues[1]) + (parseNumber(it.groupValues[2]) / 100f)
                    }
                    // 规则 3.1: (第)X话 (核心修复：不需要“第”字也能完美匹配“02话”)
                    ?: Regex("(?:第)?\\s*$NUM\\s*[话話织回章节幕折更]").find(cleanTitle)?.let {
                        parseNumber(it.groupValues[1])
                    }
                    // 规则 3.2: 第X (必须有第)
                    ?: Regex("第\\s*$NUM(?=[\\s:：,，.。!！?？|｜\\-—]|$)").find(cleanTitle)?.let {
                        parseNumber(it.groupValues[1])
                    }
                    // 规则 4: 分隔符后跟数字 (限制为纯阿拉伯数字 ARABIC)
                    ?: Regex("[-—|｜]\\s*$ARABIC(?:\\s|\\.|$)").find(cleanTitle)?.let {
                        it.groupValues[1].toFloatOrNull() ?: 0f
                    }
                    // 规则 5: 孤立数字 (限制为纯阿拉伯数字 ARABIC，拒绝把“百”当成孤立数字)
                    ?: Regex("(?:^|\\s)([^\\d\\s部季名次期天卷]?)\\s*$ARABIC\\s*([^\\d\\s部季名次期天卷]?)(?=[\\s:：—\\-,，.。!！?？|｜]|$|[^\\d])").find(
                        cleanTitle
                    )?.let {
                        it.groupValues[2].toFloatOrNull() ?: 0f
                    }
                    // 规则 6: 结尾数字 (限制为纯阿拉伯数字 ARABIC)
                    ?: Regex("$ARABIC(?!.*\\d)").find(cleanTitle)?.let {
                        if (it.groupValues[1] != ".") it.groupValues[1].toFloatOrNull()
                            ?: 0f else 0f
                    }
                    ?: 0f // 兜底

            return Math.round((baseNum + subModifier) * 1000) / 1000f
        }

        /**
         * 暴力提取标题中出现的所有数字
         */
        fun extractAllPossibleNumbers(rawTitle: String): List<Float> {
            val cleanTitle = rawTitle
                .replace(Regex("【.*?】|\\[.*?\\]|\\(.*?\\)|（.*?）|「.*?」|《.*?》"), "")
                .replace(Regex("(?i)\\bc\\d+\\b"), "")
            // 抓出所有范围在 [0, 999) 的有效数字
            val matches = Regex(NUM)
                .findAll(cleanTitle)
                .map { parseNumber(it.groupValues[1]) }
                .filter { it in 0f..<999f }
                .toList()

            val pool = mutableSetOf<Float>()
            pool.addAll(matches)

            for (i in 0 until matches.size) {
                for (j in 0 until matches.size) {
                    if (i == j) continue

                    val major = matches[i]
                    val minor = matches[j]

                    var divisor = 10f
                    while (minor >= divisor) {
                        divisor *= 10f
                    }
                    pool.add(major + minor / divisor)

                    pool.add(major + minor / (divisor * 10f))
                }
            }
            return pool.toList()
        }

        /**
         * 从 URL 提取 tid (用于去重唯一键)
         */
        fun extractTidFromUrl(url: String): String? {
            val match = Regex("tid=(\\d+)").find(url) ?: Regex("thread-(\\d+)-").find(url)
            return match?.groupValues?.get(1)
        }

        /**
         * 将中文数字/阿拉伯数字/全角/罗马/圆圈 统一解析为 Float 浮点数
         */
        private fun parseNumber(numStr: String): Float {
            // 1. 标准半角阿拉伯数字
            numStr.toFloatOrNull()?.let { return it }

            // 2. 全角数字转半角
            val halfWidthStr = numStr.map {
                if (it in '０'..'９') (it.code - '０'.code + '0'.code).toChar()
                else if (it == '．') '.'
                else it
            }.joinToString("")
            halfWidthStr.toFloatOrNull()?.let { return it }

            // 3. 特殊符号映射字典
            val specialMap = mapOf(
                '①' to 1f, '②' to 2f, '③' to 3f, '④' to 4f, '⑤' to 5f,
                '⑥' to 6f, '⑦' to 7f, '⑧' to 8f, '⑨' to 9f, '⑩' to 10f,
                '⑪' to 11f, '⑫' to 12f, '⑬' to 13f, '⑭' to 14f, '⑮' to 15f,
                '⑯' to 16f, '⑰' to 17f, '⑱' to 18f, '⑲' to 19f, '⑳' to 20f,
                'Ⅰ' to 1f, 'Ⅱ' to 2f, 'Ⅲ' to 3f, 'Ⅳ' to 4f, 'Ⅴ' to 5f,
                'Ⅵ' to 6f, 'Ⅶ' to 7f, 'Ⅷ' to 8f, 'Ⅸ' to 9f, 'Ⅹ' to 10f,
                'Ⅺ' to 11f, 'Ⅻ' to 12f
            )
            // 如果提取出来的刚好是单个特殊符号
            if (numStr.length == 1 && specialMap.containsKey(numStr[0])) {
                return specialMap[numStr[0]]!!
            }

            // 4. 中文数字处理
            var total = 0f
            var number = -1f
            for (i in numStr.indices) {
                val c = numStr[i]
                val v = when (c) {
                    '〇', '零' -> 0f; '一' -> 1f; '二', '两' -> 2f; '三' -> 3f
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
                        if (number == -1f) number = 1f
                        total += number * unit
                        number = -1f
                    }
                }
            }
            if (number != -1f) {
                total += number
            }

            if (total > 0f || numStr.contains(Regex("[〇零]"))) {
                return total
            }
            return -1f // 兜底：解析失败
        }
    }
}