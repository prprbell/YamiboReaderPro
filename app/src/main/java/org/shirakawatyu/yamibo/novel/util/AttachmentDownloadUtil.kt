package org.shirakawatyu.yamibo.novel.util

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri

/**
 * 附件下载分发器。
 *
 * 这里不再依赖 Android 系统 Resolver 的「仅一次 / 总是」。
 * 原因是很多浏览器 / 下载器会自己接管下载链接，系统 Resolver 往往只显示应用列表，
 * 不一定给出「仅一次 / 总是」。因此这里实现一个应用内下载方式面板：
 * - 「仅一次」：本次使用选中的下载器。
 * - 「总是」：保存为应用内默认下载器，之后同类附件直接使用它。
 * - 「系统下载器」：保留原 DownloadManager 行为，作为兜底和可选默认项。
 */
object AttachmentDownloadUtil {

    data class Request(
        val url: String,
        val fileName: String,
        val mimeType: String?,
        val userAgent: String?,
        val referer: String?,
        val cookie: String?,
        val contentDisposition: String?
    )

    private sealed class DownloadTarget {
        abstract val label: String
        abstract val persistValue: String

        data class App(
            override val label: String,
            override val persistValue: String,
            val packageName: String,
            val activityName: String,
            val icon: Drawable?
        ) : DownloadTarget()

        data class SystemDownloader(
            override val label: String = "系统下载器",
            override val persistValue: String = SYSTEM_DOWNLOADER_VALUE
        ) : DownloadTarget()
    }

    private const val SYSTEM_DOWNLOADER_VALUE = "__system_download_manager__"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context, request: Request) {
        val appContext = context.applicationContext
        mainHandler.post {
            SettingsUtil.getAttachmentDownloadTarget { savedTarget ->
                val target = restoreTarget(context, savedTarget, request)
                if (target != null) {
                    val launched = launchTarget(context, target, request)
                    if (!launched) {
                        SettingsUtil.clearAttachmentDownloadTarget()
                        Toast.makeText(appContext, "默认下载器不可用，请重新选择", Toast.LENGTH_SHORT).show()
                        showPickerOrFallback(context, request)
                    }
                } else {
                    showPickerOrFallback(context, request)
                }
            }
        }
    }

    private fun showPickerOrFallback(context: Context, request: Request) {
        val activity = context.findActivity()
        if (activity == null || activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
            startSystemDownload(context, request)
            return
        }

        val targets = queryExternalTargets(activity, request) + DownloadTarget.SystemDownloader()
        if (targets.isEmpty()) {
            startSystemDownload(activity, request)
            return
        }

        var selectedIndex = 0
        val adapter = DownloadTargetAdapter(activity, targets).apply {
            this.selectedIndex = selectedIndex
        }
        val listView = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setAdapter(adapter)
            setItemChecked(selectedIndex, true)
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                selectedIndex = position
                setItemChecked(position, true)
                adapter.selectedIndex = position
                adapter.notifyDataSetChanged()
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("文件下载方式：")
            .setView(listView)
            .setNegativeButton("取消", null)
            .setNeutralButton("仅一次", null)
            .setPositiveButton("总是", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val target = targets.getOrNull(selectedIndex) ?: return@setOnClickListener
                if (launchTarget(activity, target, request)) {
                    dialog.dismiss()
                } else {
                    Toast.makeText(activity, "无法打开该下载器", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val target = targets.getOrNull(selectedIndex) ?: return@setOnClickListener
                if (launchTarget(activity, target, request)) {
                    SettingsUtil.saveAttachmentDownloadTarget(target.persistValue)
                    dialog.dismiss()
                } else {
                    Toast.makeText(activity, "无法打开该下载器", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun restoreTarget(context: Context, savedTarget: String, request: Request): DownloadTarget? {
        if (savedTarget.isBlank()) return null
        if (savedTarget == SYSTEM_DOWNLOADER_VALUE) return DownloadTarget.SystemDownloader()

        val component = ComponentName.unflattenFromString(savedTarget) ?: return null
        val pm = context.packageManager
        val label = try {
            val info = pm.getActivityInfo(component, 0)
            info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: component.packageName

        return DownloadTarget.App(
            label = label,
            persistValue = savedTarget,
            packageName = component.packageName,
            activityName = component.className,
            icon = null
        )
    }

    private fun queryExternalTargets(context: Context, request: Request): List<DownloadTarget.App> {
        val pm = context.packageManager
        val candidates = linkedMapOf<String, DownloadTarget.App>()

        fun collect(intent: Intent) {
            val resolved = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            } catch (_: Exception) {
                emptyList<ResolveInfo>()
            }

            resolved.forEach { info ->
                val activityInfo = info.activityInfo ?: return@forEach
                val packageName = activityInfo.packageName ?: return@forEach
                val activityName = activityInfo.name ?: return@forEach
                if (packageName == context.packageName) return@forEach
                if (!activityInfo.exported) return@forEach

                val componentValue = ComponentName(packageName, activityName).flattenToString()
                if (candidates.containsKey(componentValue)) return@forEach

                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                    ?: activityInfo.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                    ?: packageName
                val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }

                candidates[componentValue] = DownloadTarget.App(
                    label = label,
                    persistValue = componentValue,
                    packageName = packageName,
                    activityName = activityName,
                    icon = icon
                )
            }
        }

        // 一些下载器只声明 URL，一些浏览器 / 文件处理器更依赖 MIME。两种都查，去重后展示。
        collect(buildViewIntent(context, request, includeMimeType = true))
        collect(buildViewIntent(context, request, includeMimeType = false))

        return candidates.values.sortedBy { it.label.lowercase() }
    }

    private fun launchTarget(context: Context, target: DownloadTarget, request: Request): Boolean {
        return when (target) {
            is DownloadTarget.SystemDownloader -> {
                startSystemDownload(context, request)
                true
            }
            is DownloadTarget.App -> {
                val intent = buildViewIntent(context, request, includeMimeType = true).apply {
                    component = ComponentName(target.packageName, target.activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: Exception) {
                    // 个别应用不接受 data + type，降级为只传 URL。
                    try {
                        val fallbackIntent = buildViewIntent(context, request, includeMimeType = false).apply {
                            component = ComponentName(target.packageName, target.activityName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallbackIntent)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
    }

    private fun buildViewIntent(
        context: Context,
        request: Request,
        includeMimeType: Boolean
    ): Intent {
        val uri = request.url.toUri()
        val mimeType = normalizeMimeType(request)
        val headers = buildHeaderBundle(request)

        return Intent(Intent.ACTION_VIEW).apply {
            if (includeMimeType) {
                setDataAndType(uri, mimeType)
            } else {
                data = uri
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_TITLE, request.fileName)
            putExtra(Intent.EXTRA_TEXT, request.url)
            putExtra("filename", request.fileName)
            putExtra("fileName", request.fileName)
            putExtra("android.intent.extra.FILE_NAME", request.fileName)
            putExtra("com.android.browser.application_id", context.packageName)
            putExtra("com.android.browser.headers", headers)
            putExtra("android.intent.extra.HTTP_HEADERS", headers)
            putExtra("android.intent.extra.USER_AGENT", request.userAgent ?: "")
            request.referer?.takeIf { it.isNotBlank() }?.let { referer ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    putExtra(Intent.EXTRA_REFERRER, referer.toUri())
                }
                putExtra("Referer", referer)
            }
        }
    }

    private fun buildHeaderBundle(request: Request): Bundle {
        return Bundle().apply {
            request.cookie?.takeIf { it.isNotBlank() }?.let { putString("Cookie", it) }
            request.userAgent?.takeIf { it.isNotBlank() }?.let { putString("User-Agent", it) }
            request.referer?.takeIf { it.isNotBlank() }?.let { putString("Referer", it) }
            putString("Accept", "text/plain,application/octet-stream,*/*")
        }
    }

    private fun normalizeMimeType(request: Request): String {
        request.mimeType?.takeIf { it.isNotBlank() && it != "*/*" }?.let { return it }

        val extFromName = request.fileName.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.lowercase()
        val extFromUrl = MimeTypeMap.getFileExtensionFromUrl(request.url)
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
        val extension = extFromName ?: extFromUrl
        val byExtension = extension?.let {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
        }
        return byExtension ?: "application/octet-stream"
    }

    private fun startSystemDownload(context: Context, request: Request) {
        try {
            val dmRequest = DownloadManager.Request(Uri.parse(request.url)).apply {
                val mimeType = normalizeMimeType(request)
                setMimeType(mimeType)
                request.cookie?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
                request.userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                request.referer?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Referer", it) }
                addRequestHeader("Accept", "text/plain,application/octet-stream,*/*")
                setTitle(request.fileName)
                setDescription("正在下载附件")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)
            }
            val dm = context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(dmRequest)
            Toast.makeText(context.applicationContext, "已交给系统下载器", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context.applicationContext, "无法下载附件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private class DownloadTargetAdapter(
        context: Context,
        private val items: List<DownloadTarget>
    ) : ArrayAdapter<DownloadTarget>(context, 0, items) {
        var selectedIndex: Int = 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val paddingH = dp(20)
                val paddingV = dp(10)
                setPadding(paddingH, paddingV, paddingH, paddingV)

                addView(ImageView(context).apply {
                    id = android.R.id.icon
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        marginEnd = dp(16)
                    }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                })

                addView(TextView(context).apply {
                    id = android.R.id.text1
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 18f
                    maxLines = 1
                })

                addView(RadioButton(context).apply {
                    id = android.R.id.checkbox
                    isClickable = false
                    isFocusable = false
                })
            }

            val iconView = row.findViewById<ImageView>(android.R.id.icon)
            val textView = row.findViewById<TextView>(android.R.id.text1)
            val radioButton = row.findViewById<RadioButton>(android.R.id.checkbox)
            textView.text = item.label
            radioButton.isChecked = position == selectedIndex
            when (item) {
                is DownloadTarget.App -> iconView.setImageDrawable(item.icon)
                is DownloadTarget.SystemDownloader -> iconView.setImageResource(android.R.drawable.stat_sys_download)
            }
            return row
        }

        private fun dp(value: Int): Int {
            return (value * context.resources.displayMetrics.density + 0.5f).toInt()
        }
    }
}
