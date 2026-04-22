package org.shirakawatyu.yamibo.novel.parser

import org.jsoup.Jsoup
import org.shirakawatyu.yamibo.novel.bean.MangaChapterItem
import org.shirakawatyu.yamibo.novel.util.manga.MangaTitleCleaner
import java.text.SimpleDateFormat
import java.util.Locale

class MangaHtmlParser {
    companion object {
        /**
         * 解析手机端HTML，寻找Tag ID
         */
        fun findTagIdsMobile(html: String): List<String> {
            val doc = Jsoup.parse(html)
            val tagLinks = doc.select("a[href*='mod=tag']")

            val ids = mutableListOf<String>()
            for (link in tagLinks) {
                val href = link.attr("href")
                val match = Regex("id=(\\d+)").find(href)
                match?.groupValues?.get(1)?.let { ids.add(it) }
            }
            return ids.distinct()
        }

        /**
         * 从URL提取UID
         */
        private fun extractUidFromUrl(url: String): String? {
            // 兼容 "uid=123" 和 "space-uid-123.html" 两种格式
            val match = Regex("uid=(\\d+)").find(url) ?: Regex("uid-(\\d+)").find(url)
            return match?.groupValues?.get(1)
        }

        /**
         * 提取1楼正文中的所有内部 TID 超链接
         */
        fun extractSamePageLinks(html: String): List<MangaChapterItem> {
            val doc = Jsoup.parse(html)
            val messageDiv = doc.select(".message").firstOrNull() ?: return emptyList()

            val result = mutableListOf<MangaChapterItem>()
            val links = messageDiv.select("a[href*='tid='], a[href*='thread-']")

            for (link in links) {
                val url = link.attr("href")
                val title = link.text()
                val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: continue
                val chapterNum = MangaTitleCleaner.extractChapterNum(title)
                val safeUrl = "https://bbs.yamibo.com/forum.php?mod=viewthread&tid=$tid&mobile=2"
                result.add(MangaChapterItem(tid, title, chapterNum, safeUrl, null, null))
            }
            return result
        }

        /**
         * 提取列表页的总页数 (强化兼容手机端)
         */
        fun extractTotalPages(html: String): Int {
            val doc = Jsoup.parse(html)

            val mobileOptions = doc.select("select#dumppage option")
            if (mobileOptions.isNotEmpty()) {
                val maxPage =
                    mobileOptions.mapNotNull { it.attr("value").toIntOrNull() }.maxOrNull()
                if (maxPage != null) return maxPage
            }

            val titleAttr = doc.select(".pg label span").attr("title")
            val match = Regex("(\\d+)").find(titleAttr)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }

            val pageLinks = doc.select(".pg a:not(.nxt):not(.prev)")
            if (pageLinks.isNotEmpty()) {
                val maxPage = pageLinks.mapNotNull { it.text().toIntOrNull() }.maxOrNull()
                if (maxPage != null) return maxPage
            }

            return 1
        }

        /**
         * 提取搜索结果页的searchid
         */
        fun extractSearchId(html: String): String? {
            val doc = Jsoup.parse(html)
            val nextLink = doc.select("div.page a[href*='searchid='], div.pg a[href*='searchid=']")
                .firstOrNull()?.attr("href")
            if (nextLink != null) {
                val match = Regex("searchid=(\\d+)").find(nextLink)
                return match?.groupValues?.get(1)
            }
            return null
        }

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        private fun parsePublishTime(dateStr: String?): Long {
            if (dateStr.isNullOrBlank()) return 0L
            return try {
                dateFormat.parse(dateStr.trim())?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }

        /**
         * 解析Tag列表页(PC端)或搜索结果页(手机端)，转换为统一的ChapterItem列表
         */
        fun parseListHtml(html: String, groupIndex: Int = 0): List<MangaChapterItem> {
            val doc = Jsoup.parse(html)
            val result = mutableListOf<MangaChapterItem>()

            // 处理 PC 端 Tag 页面
            if (doc.select("body.pg_tag").isNotEmpty() || doc.select(".bm_c table").isNotEmpty()) {
                val rows = doc.select(".bm_c table tr")
                for (row in rows) {
                    if (row.select("th h2").isNotEmpty()) continue

                    val titleElement = row.select("th a").firstOrNull() ?: continue
                    val url = titleElement.attr("href")
                    val title = titleElement.text()

                    val authorTd = row.select("td.by").getOrNull(1)
                    val authorElement = authorTd?.select("cite a")?.firstOrNull()
                    val authorName = authorElement?.text()
                    val authorUid = authorElement?.attr("href")?.let { extractUidFromUrl(it) }

                    val timeStr = authorTd?.select("em span")?.firstOrNull()?.text()
                        ?: authorTd?.select("em")?.firstOrNull()?.text()

                    val cleanTimeStr = timeStr?.replace(Regex("[^0-9-]"), "")
                    val publishTime = parsePublishTime(cleanTimeStr)

                    val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: continue
                    val chapterNum = MangaTitleCleaner.extractChapterNum(title)

                    result.add(
                        MangaChapterItem(
                            tid,
                            title,
                            chapterNum,
                            url,
                            authorUid,
                            authorName,
                            groupIndex,
                            publishTime
                        )
                    )
                }
            }
            // 处理手机端 Search 搜索结果页面
            else if (doc.select("body#search").isNotEmpty() || doc.select(".threadlist li.list")
                    .isNotEmpty()
            ) {
                val items = doc.select(".threadlist li.list")
                for (item in items) {
                    val titleLink = item.select("a[href*='tid=']").firstOrNull() ?: continue
                    val url = titleLink.attr("href")
                    val title = titleLink.select(".threadlist_tit em").text()

                    val authorElement = item.select(".muser h3 a").firstOrNull()
                    val authorName = authorElement?.text()
                    val authorUid = authorElement?.attr("href")?.let { extractUidFromUrl(it) }

                    val tid = MangaTitleCleaner.extractTidFromUrl(url) ?: continue
                    val chapterNum = MangaTitleCleaner.extractChapterNum(title)

                    result.add(
                        MangaChapterItem(
                            tid,
                            title,
                            chapterNum,
                            url,
                            authorUid,
                            authorName,
                            groupIndex
                        )
                    )
                }
            }

            return result
        }

        /**
         * 异常嗅探：防止把防灌水页面当做空目录解析
         */
        fun isFloodControlOrError(html: String): Boolean {
            // 搜不到不是错误，返回空列表即可，不要抛异常
            if (html.contains("没有找到匹配结果")) return false

            return html.contains("只能进行一次搜索") ||
                    html.contains("防灌水") ||
                    html.contains("指定的搜索词长度")
        }
    }
}