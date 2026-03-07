package org.shirakawatyu.yamibo.novel.util

class MangaTitleCleaner {
    companion object {

        /**
         * 提取纯净书名 (用于后续去重和搜索)
         */
        fun getCleanBookName(rawTitle: String): String {
            var clean = rawTitle

            // 1. 【新增】预处理：强制切掉已知的论坛/站点后缀
            // 匹配 " - " 后面跟着 "区" 或者 "百合会" 等关键词及其后续内容
            clean = clean.replace(Regex("\\s+-\\s+.*?(中文百合漫画区|百合会|论坛).*$"), "")

            // 2. 无差别干掉所有的 【汉化组】、[作者名]、[英文原名] 等外围标签
            // 使用非贪婪匹配
            clean = clean.replace(Regex("【.*?】|\\[.*?\\]"), "")

            // 干掉展会标签 (C107) 等
            clean = clean.replace(Regex("\\([Cc]\\d+\\)|（[Cc]\\d+）"), "")

            // 3. 【核心强化】：截断章节标记及其后面的所有内容
            // 增加了：#, S(第几季), EP(集数), Vol, Ch, 以及连字符接数字的情况
            val chapterMarkerPattern = Regex(
                "(?i)(" + // (?i) 表示忽略大小写
                        "第\\s*[\\d\\.\\-零一二两三四五六七八九十百千]+\\s*[话話回卷]|" +
                        "\\s*[#＃]\\s*\\d+|" +      // 匹配 #129
                        "\\s*S\\d+(\\s*EP\\d+)?|" + // 匹配 S3 EP01
                        "\\s*EP\\d+|" +             // 匹配 EP01
                        "\\s*Vol\\.?\\s*\\d+|" +    // 匹配 Vol.01
                        "\\s*Ch\\.?\\s*\\d+|" +     // 匹配 Ch.01
                        "\\s+番外|\\s+特典|\\s+附录|\\s+短篇|\\s+单行本|" +
                        "\\s+(前篇|中篇|后篇|上|中|下)" +
                        ")"
            )
            val markerMatch = chapterMarkerPattern.find(clean)
            if (markerMatch != null) {
                clean = clean.substring(0, markerMatch.range.first)
            }

            // 4. 处理末尾残留的孤立数字 (例如: "书名 16")
            // 匹配：空格 + 数字 + 结尾
            clean = clean.replace(Regex("\\s+\\d+(\\.\\d+)?\\s*$"), "")

            // 5. 最终清洗两端残留的无用符号 (加上了 #, :, 等)
            clean = clean.replace(Regex("^[\\s\\-|/\\)#]+|[\\s\\-|/\\(#:]+$"), "")

            return clean.trim()
        }

        /**
         * 提取话数 (用于排序，必须返回 Float)
         */
        fun extractChapterNum(rawTitle: String): Float {
            // 先去掉各种括号，防止把 [阅读权限 20] 当成第20话
            val cleanTitle = rawTitle.replace(Regex("【.*?】|\\[.*?\\]|\\(.*?\\)|（.*?）"), "")

            // 规则 1: (第)?X话其Y (e.g., "1话其2" -> 1.02f)
            val matchQi =
                Regex("(?:第)?\\s*([\\d\\.]+|[零一二两三四五六七八九十百千]+)\\s*[话話]\\s*其\\s*([\\d\\.]+|[零一二两三四五六七八九十百千]+)").find(
                    cleanTitle
                )
            if (matchQi != null) {
                val p1 = parseNumber(matchQi.groupValues[1])
                val p2 = parseNumber(matchQi.groupValues[2])
                // 【稳健计算逻辑】：主话数 + (子话数 / 100f)
                return p1 + (p2 / 100f)
            }

            // 规则 2: 第X-Y话 (e.g., "第9-2话" -> 9.02f)
            val matchDash =
                Regex("第\\s*([\\d]+|[零一二两三四五六七八九十百千]+)[\\-]([\\d]+|[零一二两三四五六七八九十百千]+)\\s*[话話]").find(
                    cleanTitle
                )
            if (matchDash != null) {
                val p1 = parseNumber(matchDash.groupValues[1])
                val p2 = parseNumber(matchDash.groupValues[2])
                return p1 + (p2 / 100f)
            }

            // 规则 3: 第X话 / 第X.Y话 (e.g., "第8.5话", "第八话" -> 8.5, 8.0)
            val matchStandard =
                Regex("第\\s*([\\d\\.]+|[零一二两三四五六七八九十百千]+)\\s*[话話]").find(cleanTitle)
            if (matchStandard != null) {
                return parseNumber(matchStandard.groupValues[1])
            }

            // 规则 4: 番外 / 特典 / 附录 (赋予极大的基础值，让它们排在正篇后面)
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

            // 规则 5: 前/中/后篇 赋予微小小数以便在同一话内排序
            if (cleanTitle.contains("前篇") || cleanTitle.contains("上")) return 0.1f
            if (cleanTitle.contains("中篇") || cleanTitle.contains("中")) return 0.2f
            if (cleanTitle.contains("后篇") || cleanTitle.contains("下")) return 0.3f

            // 规则 6: 啥都没写，只在最后丢个数字
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

        /**
         * 【新增】将中文数字/阿拉伯数字统一解析为 Float 浮点数
         * 完美支持 "八", "十一", "一百零八" 等汉化组常见编号
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