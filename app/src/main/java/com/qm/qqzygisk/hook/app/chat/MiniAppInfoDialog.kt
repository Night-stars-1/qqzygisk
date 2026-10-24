package com.qm.qqzygisk.hook.app.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.utils.injectModuleAppResources

/**
 * 小程序卡片信息弹窗：概览与格式化 JSON 共用同一个对话框、同一份内容，切换不叠层。
 *
 * 只展示 bytesData 原文，不联网、不转网页、不自动打开任何链接；
 * “复制JSON”使用 [MiniAppCardInfo.rawJson]，与宿主返回的文本完全一致。
 */
internal object MiniAppInfoDialog {
    private const val TITLE_LABEL = "小程序信息"
    private const val OVERVIEW_LABEL = "概览"
    private const val RAW_JSON_LABEL = "原始 JSON"
    private const val COPY_LABEL = "复制JSON"
    private const val CLOSE_LABEL = "关闭"
    private const val CLIP_LABEL = "小程序卡片 JSON"

    fun show(host: Context, info: MiniAppCardInfo) {
        host.injectModuleAppResources()
        val moduleLoader = MiniAppInfoDialog::class.java.classLoader ?: host.classLoader
        val themed = ModuleContext(host, R.style.Theme_QQZygisk_MaterialDialog, moduleLoader)
        create(themed, info).show()
    }

    private fun create(context: Context, info: MiniAppCardInfo): AlertDialog {
        val content = buildTabs(context, info)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(TITLE_LABEL)
            .setView(content)
            .setPositiveButton(COPY_LABEL, null)
            .setNegativeButton(CLOSE_LABEL, null)
            .create()
        dialog.setOnShowListener {
            val metrics = context.resources.displayMetrics
            val width = (metrics.widthPixels * 0.92f).toInt()
                .coerceAtMost(context.miniAppDp(600))
                .coerceAtLeast(context.miniAppDp(260))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            val primary = ContextCompat.getColor(context, R.color.qqz_primary)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(primary)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(primary)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                copyJson(context, info.rawJson)
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        return dialog
    }

    private fun buildTabs(context: Context, info: MiniAppCardInfo): LinearLayout {
        val overview = buildOverview(context, info)
        val raw = buildRaw(context, info)

        val overviewTab = tabView(context, OVERVIEW_LABEL)
        val rawTab = tabView(context, RAW_JSON_LABEL)

        val metrics = context.resources.displayMetrics
        val scrollHeight = (metrics.heightPixels * 0.45f).toInt()
            .coerceAtMost(context.miniAppDp(480))
            .coerceAtLeast(context.miniAppDp(160))
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(overview, matchWrap())
                    addView(raw, matchWrap())
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fun select(selectRaw: Boolean) {
            overview.visibility = if (selectRaw) View.GONE else View.VISIBLE
            raw.visibility = if (selectRaw) View.VISIBLE else View.GONE
        }
        val tabs = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            addView(overviewTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(rawTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) select(checkedId == rawTab.id)
            }
            check(overviewTab.id)
        }
        select(false)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.miniAppDp(24), 0, context.miniAppDp(24), 0)
            addView(
                tabs,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = context.miniAppDp(12) },
            )
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scrollHeight),
            )
        }
        return root
    }

    private fun buildOverview(context: Context, info: MiniAppCardInfo): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.miniAppDp(6), 0, context.miniAppDp(10))
        }
        addField(column, context, "应用（app）", info.app)
        info.view?.let { addField(column, context, "视图（view）", it) }
        info.prompt?.let { addField(column, context, "提示（prompt）", it) }

        when {
            info.metadataPath == null -> addNote(column, context, "未提供可读取的 meta.detail_1 / meta.miniapp")
            info.fields.isEmpty() -> addNote(column, context, "${info.metadataPath} 没有可展示的文本字段")
            else -> {
                addSection(column, context, info.metadataPath)
                info.fields.forEach { field ->
                    val label = if (field.label == field.key) field.key else "${field.label}（${field.key}）"
                    val value = addField(column, context, label, field.value)
                    // 链接字段只是一段可选中的原文，不设置点击/自动识别。
                    value.isClickable = false
                }
            }
        }
        return column
    }

    private fun buildRaw(context: Context, info: MiniAppCardInfo): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, context.miniAppDp(6), 0, context.miniAppDp(10))
        }
        column.addView(
            TextView(context).apply {
                text = info.prettyJson
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.qqz_on_surface))
                setTextIsSelectable(true)
                setPadding(0, context.miniAppDp(4), 0, 0)
            },
            matchWrap(),
        )
        return column
    }

    private fun addField(
        column: LinearLayout,
        context: Context,
        label: String,
        value: String,
    ): TextView {
        column.addView(
            TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.qqz_on_surface_variant))
                setPadding(0, context.miniAppDp(10), 0, context.miniAppDp(2))
            },
            matchWrap(),
        )
        return TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context, R.color.qqz_on_surface))
            setTextIsSelectable(true)
        }.also { column.addView(it, matchWrap()) }
    }

    private fun addSection(column: LinearLayout, context: Context, title: String) {
        column.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.qqz_primary))
                setPadding(0, context.miniAppDp(16), 0, 0)
            },
            matchWrap(),
        )
    }

    private fun addNote(column: LinearLayout, context: Context, note: String) {
        column.addView(
            TextView(context).apply {
                text = note
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(context, R.color.qqz_on_surface_variant))
                setPadding(0, context.miniAppDp(12), 0, 0)
            },
            matchWrap(),
        )
    }

    private fun tabView(context: Context, label: String): MaterialButton = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = context.miniAppDp(48)
        minimumWidth = 0
        insetTop = 0
        insetBottom = 0
        cornerRadius = context.miniAppDp(22)
        setPadding(context.miniAppDp(12), 0, context.miniAppDp(12), 0)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        backgroundTintList = ColorStateList(states, intArrayOf(
            ContextCompat.getColor(context, R.color.qqz_primary_container),
            Color.TRANSPARENT,
        ))
        setTextColor(ColorStateList(states, intArrayOf(
            ContextCompat.getColor(context, R.color.qqz_on_primary_container),
            ContextCompat.getColor(context, R.color.qqz_on_surface_variant),
        )))
        strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.qqz_outline_variant))
        strokeWidth = context.miniAppDp(1)
    }

    private fun copyJson(context: Context, rawJson: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(context, "当前环境无法访问剪贴板", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, rawJson))
            Toast.makeText(context, "已复制 JSON", Toast.LENGTH_SHORT).show()
        } catch (_: RuntimeException) {
            Toast.makeText(context, "无法复制 JSON，请检查宿主的剪贴板权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
}

private fun Context.miniAppDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
