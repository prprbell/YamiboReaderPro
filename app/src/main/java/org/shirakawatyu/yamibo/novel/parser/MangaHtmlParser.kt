package org.shirakawatyu.yamibo.novel.parser

import org.jsoup.Jsoup
import org.shirakawatyu.yamibo.novel.bean.MangaChapterItem
import org.shirakawatyu.yamibo.novel.util.MangaTitleCleaner

class MangaHtmlParser {
    companion object {
        /**
         * 解析手机端 HTML，寻找 Tag ID
         */
        fun findTagIdsMobile(html: String): List<String> {
            val doc = Jsoup.parse(html)
            // 选出所有包含 mod=tag 的 a 标签
            val tagLinks = doc.select("a[href*='mod=tag']")

            val ids = mutableListOf<String>()
            for (link in tagLinks) {
                val href = link.attr("href")
                val match = Regex("id=(\\d+)").find(href)
                match?.groupValues?.get(1)?.let { ids.add(it) }
            }
            // 返回去重后的 Tag ID 列表
            return ids.distinct()
        }

        /**
         * 从 URL 提取 UID (新增辅助方法)
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

            // 1. 【新增优先】优先从手机端的下拉框中提取最大页数
            val mobileOptions = doc.select("select#dumppage option")
            if (mobileOptions.isNotEmpty()) {
                val maxPage =
                    mobileOptions.mapNotNull { it.attr("value").toIntOrNull() }.maxOrNull()
                if (maxPage != null) return maxPage
            }

            // 2. 尝试从 PC 端 <span title="共 3 页"> 提取
            val titleAttr = doc.select(".pg label span").attr("title")
            val match = Regex("(\\d+)").find(titleAttr)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }

            // 3. 保底策略：提取普通数字页码取最大值
            val pageLinks = doc.select(".pg a:not(.nxt):not(.prev)")
            if (pageLinks.isNotEmpty()) {
                val maxPage = pageLinks.mapNotNull { it.text().toIntOrNull() }.maxOrNull()
                if (maxPage != null) return maxPage
            }

            return 1
        }

        /**
         * 【新增】：提取搜索结果页的 searchid (用于翻页请求)
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

        /**
         * 解析 Tag 列表页(PC端) 或 搜索结果页(手机端)，转换为统一的 ChapterItem 列表
         */
        fun parseListHtml(html: String, groupIndex: Int = 0): List<MangaChapterItem> {
            val doc = Jsoup.parse(html)
            val result = mutableListOf<MangaChapterItem>()

            // ==========================================
            // 分支 1：处理 PC 端 Tag 页面
            // ==========================================
            if (doc.select("body.pg_tag").isNotEmpty() || doc.select(".bm_c table").isNotEmpty()) {
                // 【修复】去掉 tbody，直接匹配 table 下所有的 tr，避免 Jsoup 兼容性问题
                val rows = doc.select(".bm_c table tr")
                for (row in rows) {
                    // 跳过表头 <tr><th><h2>相关帖子</h2></th></tr>
                    if (row.select("th h2").isNotEmpty()) continue

                    // 提取标题和链接 (在 <th> 下的 <a> 中)
                    val titleElement = row.select("th a").firstOrNull() ?: continue
                    val url = titleElement.attr("href")
                    val title = titleElement.text() // Jsoup 会自动剥离HTML标签，得到纯文本

                    // 提取作者和 UID (在 <td class="by"> <cite> <a> 中)
                    val authorElement = row.select("td.by cite a").firstOrNull()
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
            // ==========================================
            // 分支 2：处理手机端 Search 搜索结果页面
            // 依据：<body id="search"> 或包含 <li class="list">
            // ==========================================
            else if (doc.select("body#search").isNotEmpty() || doc.select(".threadlist li.list")
                    .isNotEmpty()
            ) {
                val items = doc.select(".threadlist li.list")
                for (item in items) {
                    // 提取链接和标题
                    val titleLink = item.select("a[href*='tid=']").firstOrNull() ?: continue
                    val url = titleLink.attr("href")
                    // 标题在 <div class="threadlist_tit"> <em> 下
                    val title = titleLink.select(".threadlist_tit em").text()

                    // 提取作者和 UID
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