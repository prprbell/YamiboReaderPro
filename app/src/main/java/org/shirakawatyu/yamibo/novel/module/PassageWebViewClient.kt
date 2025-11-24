package org.shirakawatyu.yamibo.novel.module

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.RequiresApi
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONException

/**
 * 小说界面的 WebViewClient，用于处理小说内容页面的加载逻辑。
 */
class PassageWebViewClient(
    val onFinished: (
        success: Boolean,
        contentHtml: String,
        url: String?,
        maxPage: Int,
        title: String?
    ) -> Unit
) :
    YamiboWebViewClient() {
    private val logTag = "PassageWebViewClient"

    // 用于防止onPageFinished在onReceivedError之后错误地报告成功
    private var hasErrorOccurred = false
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null) {
            // Log.i(logTag, url)
        }
        hasErrorOccurred = false
        super.onPageStarted(view, url, favicon)
    }

    /**
     * 对 JavaScript 执行结果进行反序列化处理，去除 JSON 转义字符。
     *
     * @param jsString JavaScript 返回的原始字符串。
     * @return 处理后的字符串内容。
     */
    private fun unescapeJsResult(jsString: String?): String {
        if (jsString == null || jsString == "null") {
            return ""
        }

        return try {
            JSON.parse(jsString) as? String ?: ""
        } catch (e: JSONException) {
            // 尝试去除 JSON 转义字符
            Log.w(logTag, "JS result was not a valid JSON string, returning raw: $jsString", e)
            if (jsString.length >= 2 && jsString.startsWith("\"") && jsString.endsWith("\"")) {
                jsString.substring(1, jsString.length - 1)
            } else {
                jsString
            }
        } catch (e: Exception) {
            Log.e(logTag, "Failed to unescape JS result: $jsString", e)
            jsString
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (hasErrorOccurred) {
            Log.w(logTag, "onPageFinished called, but an error already occurred. Ignoring.")
            super.onPageFinished(view, url)
            return // 立即退出
        }

        // 如果URL不包含authorid，说明是“所有人”页面
        if (url != null && !url.contains("authorid")) {
            // 点击“只看楼主”按钮
            view?.evaluateJavascript("document.getElementsByClassName('nav-more-item')[0]?.click();") {}
            super.onPageFinished(view, url)
            return
        }

        // 已进入“只看楼主”页面，提取正文
        val contentJs = """
            (function() {
                var el = document.getElementsByClassName('viewthread')[0];
                return el ? el.innerHTML : '<html><body>[Error] Content element not found</body></html>';
            })()
        """.trimIndent()

        // 提取最大页码
        val maxPageJs = """
            (function() {        
                var selectElements = document.querySelectorAll('select#dumppage');
                var selectElement = null;
                if (selectElements.length > 0) {
                    selectElement = selectElements[0];
                } else {
                    selectElement = document.getElementById('dumppage');
                    if (selectElement && selectElement.tagName !== 'SELECT') {
                        selectElement = null;
                    }
                }
                if (selectElement) {
                    if (selectElement.options && selectElement.options.length > 0) {
                        var lastOption = selectElement.options[selectElement.options.length - 1];
                        return lastOption.value;
                    }
                }
                return "1";
            })()
        """.trimIndent()
        // 获取标题
        val titleJs = """
            (function() {
                var mobileT = document.getElementsByClassName('view_tit')[0];
                if(mobileT && mobileT.innerText) return mobileT.innerText;
                
                var t = document.getElementById('thread_subject');
                if(t && t.innerText) return t.innerText;
              
                return null;
            })()
        """.trimIndent()
        // 执行JavaScript获取页面内容
        view?.evaluateJavascript(contentJs) { contentResult ->
            val contentHtml = "<html>${unescapeJsResult(contentResult)}</html>"
            // 执行JavaScript获取最大页数
            view.evaluateJavascript(maxPageJs) { maxPageResult ->

                val maxPageString = unescapeJsResult(maxPageResult)
                val maxPage = maxPageString.toIntOrNull() ?: 1
                view.evaluateJavascript(titleJs) { titleResult ->
                    val title = unescapeJsResult(titleResult)

                    if (!hasErrorOccurred) {
                        onFinished.invoke(true, contentHtml, url, maxPage, title)
                    }
                    super.onPageFinished(view, url)
                }
            }
        }
    }

    /**
     * 处理加载失败
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            if (hasErrorOccurred) return
            hasErrorOccurred = true

            Log.e(logTag, "Main frame error: ${error?.description}")
            onFinished.invoke(
                false,
                "<html><body>[Error] 页面加载失败: ${error?.description}</body></html>",
                request.url.toString(),
                1,
                null
            )
        }
    }

    /**
     * 处理 HTTP 错误
     */
    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            if (hasErrorOccurred) return
            hasErrorOccurred = true

            Log.e(logTag, "Main frame HTTP error: ${errorResponse?.statusCode}")
            onFinished.invoke(
                false,
                "<html><body>[Error] 页面加载失败: ${errorResponse?.statusCode}</body></html>",
                request.url.toString(),
                1,
                null
            )
        }
    }
}