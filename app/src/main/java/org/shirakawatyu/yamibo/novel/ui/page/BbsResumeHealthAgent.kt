package org.shirakawatyu.yamibo.novel.ui.page

import com.alibaba.fastjson2.JSON

/**
 * BBS WebView 页面内健康代理。
 *
 * 设计目标：
 * 1. App 回到前台时先静默 repair，再 probe；
 * 2. repair/probe 都是幂等的，不触发主框架 reload；
 * 3. Native 只在 Fatal 时升级到骨架屏 + 重新加载。
 */
object BbsResumeHealthAgent {
    enum class Status {
        Healthy,
        Fatal,
        Unknown
    }

    data class Snapshot(
        val status: Status,
        val href: String?,
        val readyState: String?,
        val hasDocumentElement: Boolean,
        val hasBody: Boolean,
        val bodyTextLength: Int,
        val hasForumContent: Boolean,
        val hasErrorPageText: Boolean,
        val reason: String?
    )

    /**
     * 在页面内安装一个极小的常驻 health agent，并立刻做一次静默修复。
     * 注意：这里不放业务重逻辑；业务脚本仍由 PageJsScripts 重注入。
     */
    val RESUME_REPAIR_AND_PROBE_JS: String = """
        (function() {
            function stringify(obj) {
                try { return JSON.stringify(obj); }
                catch (e) { return '{"status":"fatal","reason":"json_stringify_failed"}'; }
            }

            try {
                if (!window.__yamiboHealthAgent) {
                    window.__yamiboHealthAgent = {
                        version: 3,
                        installedAt: Date.now(),
                        lastResumeAt: 0,
                        lastRepairAt: 0,
                        lastSnapshot: null
                    };
                }

                var agent = window.__yamiboHealthAgent;
                agent.version = 3;
                agent.lastResumeAt = Date.now();

                function reloadBrokenImages() {
                    try {
                        var imgs = document.images ? Array.prototype.slice.call(document.images) : [];
                        imgs.forEach(function(img) {
                            try {
                                var src = img.currentSrc || img.src || '';
                                if (!src) return;
                                var rect = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
                                var visibleEnough = !rect || rect.bottom >= -400 && rect.top <= (window.innerHeight + 800);
                                var broken = img.complete && (img.naturalWidth === 0 || img.naturalHeight === 0);
                                if (broken && visibleEnough && !img.dataset.yamiboRepairing) {
                                    img.dataset.yamiboRepairing = '1';
                                    var joiner = src.indexOf('?') >= 0 ? '&' : '?';
                                    img.src = src.replace(/([?&])__yamibo_img_retry=\d+/, '${'$'}1').replace(/[?&]${'$'}, '') + joiner + '__yamibo_img_retry=' + Date.now();
                                    setTimeout(function() { try { delete img.dataset.yamiboRepairing; } catch (_) {} }, 1500);
                                }
                            } catch (_) {}
                        });
                    } catch (_) {}
                }

                function ensureViewportStable() {
                    try {
                        document.documentElement.style.webkitTextSizeAdjust = '100%';
                        document.body && (document.body.style.webkitTextSizeAdjust = '100%');
                    } catch (_) {}
                }

                function snapshot() {
                    var href = String(location.href || '');
                    var readyState = String(document.readyState || '');
                    var hasDocumentElement = !!document.documentElement;
                    var hasBody = !!document.body;
                    var bodyText = hasBody ? String(document.body.innerText || document.body.textContent || '') : '';
                    var normalizedBodyText = bodyText.replace(/\s+/g, ' ').trim();
                    var bodyTextLength = normalizedBodyText.length;
                    var hasForumContent = !!document.querySelector([
                        '#ct', '.wp', '.bm', '.bm_c', '#threadlist', '#postlist',
                        '.threadlist', '.postlist', '.postmessage', '.message', '.mn', '.fl'
                    ].join(','));
                    var lower = normalizedBodyText.toLowerCase();
                    var hasErrorPageText =
                        lower.indexOf('web page not available') >= 0 ||
                        lower.indexOf('err_') >= 0 ||
                        lower.indexOf('网页无法打开') >= 0 ||
                        lower.indexOf('找不到网页') >= 0 ||
                        lower.indexOf('无法访问此网站') >= 0 ||
                        lower.indexOf('this site can') >= 0 && lower.indexOf('be reached') >= 0;

                    var readyEnough = readyState === 'interactive' || readyState === 'complete';
                    var contentEnough = hasForumContent || bodyTextLength >= 80;
                    var usableUrl = href && href !== 'about:blank' && href.indexOf('data:') !== 0;
                    var healthy = usableUrl && hasDocumentElement && hasBody && readyEnough && contentEnough && !hasErrorPageText;

                    var result = {
                        status: healthy ? 'healthy' : 'fatal',
                        href: href,
                        readyState: readyState,
                        hasDocumentElement: hasDocumentElement,
                        hasBody: hasBody,
                        bodyTextLength: bodyTextLength,
                        hasForumContent: hasForumContent,
                        hasErrorPageText: hasErrorPageText,
                        reason: healthy ? '' : [
                            usableUrl ? '' : 'bad_url',
                            hasDocumentElement ? '' : 'missing_document_element',
                            hasBody ? '' : 'missing_body',
                            readyEnough ? '' : 'not_ready',
                            contentEnough ? '' : 'content_too_small',
                            hasErrorPageText ? 'webview_error_text' : ''
                        ].filter(Boolean).join('|')
                    };
                    agent.lastSnapshot = result;
                    return result;
                }

                agent.repair = function() {
                    agent.lastRepairAt = Date.now();
                    ensureViewportStable();
                    reloadBrokenImages();
                };
                agent.snapshot = snapshot;
                agent.resumeAndProbe = function() {
                    agent.repair();
                    return snapshot();
                };

                return stringify(agent.resumeAndProbe());
            } catch (e) {
                return stringify({
                    status: 'fatal',
                    href: String((location && location.href) || ''),
                    readyState: String((document && document.readyState) || ''),
                    hasDocumentElement: !!(document && document.documentElement),
                    hasBody: !!(document && document.body),
                    bodyTextLength: 0,
                    hasForumContent: false,
                    hasErrorPageText: false,
                    reason: String(e && (e.message || e))
                });
            }
        })();
    """.trimIndent()

    fun parse(rawResult: String?): Snapshot {
        if (rawResult.isNullOrBlank() || rawResult == "null") {
            return Snapshot(
                status = Status.Unknown,
                href = null,
                readyState = null,
                hasDocumentElement = false,
                hasBody = false,
                bodyTextLength = 0,
                hasForumContent = false,
                hasErrorPageText = false,
                reason = "empty_result"
            )
        }

        return try {
            val cleanJson = if (rawResult.startsWith("\"")) {
                JSON.parse(rawResult) as? String ?: rawResult
            } else {
                rawResult
            }
            val obj = JSON.parseObject(cleanJson)
            val status = when (obj.getString("status")?.lowercase()) {
                "healthy" -> Status.Healthy
                "fatal" -> Status.Fatal
                else -> Status.Unknown
            }
            Snapshot(
                status = status,
                href = obj.getString("href"),
                readyState = obj.getString("readyState"),
                hasDocumentElement = obj.getBooleanValue("hasDocumentElement"),
                hasBody = obj.getBooleanValue("hasBody"),
                bodyTextLength = obj.getIntValue("bodyTextLength"),
                hasForumContent = obj.getBooleanValue("hasForumContent"),
                hasErrorPageText = obj.getBooleanValue("hasErrorPageText"),
                reason = obj.getString("reason")
            )
        } catch (_: Throwable) {
            Snapshot(
                status = Status.Unknown,
                href = null,
                readyState = null,
                hasDocumentElement = false,
                hasBody = false,
                bodyTextLength = 0,
                hasForumContent = false,
                hasErrorPageText = false,
                reason = "parse_failed"
            )
        }
    }
}
