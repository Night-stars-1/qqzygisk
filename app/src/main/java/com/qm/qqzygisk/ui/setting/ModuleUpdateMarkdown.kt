package com.qm.qqzygisk.ui.setting

import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme

/** Native spans, not raw Markdown text or a WebView executing remote HTML. */
@Composable
internal fun ModuleUpdateMarkdown(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current.density
    val renderer = remember(context, colors, density) {
        val table = TableTheme.buildWithDefaults(context)
            .tableBorderColor(colors.outlineVariant.toArgb())
            .tableBorderWidth(density.toInt().coerceAtLeast(1))
            .tableCellPadding((6f * density).toInt())
            .tableHeaderRowBackgroundColor(colors.surfaceContainerHigh.toArgb())
            .tableOddRowBackgroundColor(colors.surfaceContainerLow.toArgb())
            .tableEvenRowBackgroundColor(android.graphics.Color.TRANSPARENT)
            .build()
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(table))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(colors.primary.toArgb())
                        .codeTextColor(colors.onSurface.toArgb())
                        .codeBackgroundColor(colors.surfaceContainerHigh.toArgb())
                        .codeBlockTextColor(colors.onSurface.toArgb())
                        .codeBlockBackgroundColor(colors.surfaceContainerHigh.toArgb())
                        .headingBreakColor(colors.outlineVariant.toArgb())
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val uri = Uri.parse(link)
                        if (uri.scheme != "https") {
                            Toast.makeText(view.context, "仅支持 HTTPS 链接", Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)
                                    .addCategory(Intent.CATEGORY_BROWSABLE)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (error: Exception) {
                                Toast.makeText(view.context, "无法打开链接：${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            })
            .build()
    }
    // Download progress may recompose its parent every 700ms; parse each document only once.
    val rendered = remember(renderer, text) { renderer.toMarkdown(text) }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextView(viewContext).apply {
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                movementMethod = TableAwareMovementMethod.create()
            }
        },
        update = { view ->
            view.setTextColor(colors.onSurfaceVariant.toArgb())
            view.setLinkTextColor(colors.primary.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, typography.fontSize.value)
            view.setLineSpacing(0f, typography.lineHeight.value / typography.fontSize.value)
            if (view.tag !== rendered) {
                renderer.setParsedMarkdown(view, rendered)
                view.tag = rendered
            }
        },
    )
}
