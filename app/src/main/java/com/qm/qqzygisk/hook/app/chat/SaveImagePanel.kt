package com.qm.qqzygisk.hook.app.chat

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.utils.AnimatedImageLoader
import com.qm.qqzygisk.hook.utils.ImageDownloader
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ImagePanelAction(
    val id: String,
    val label: String,
    val iconRes: Int,
    val contentDescription: String = label,
    val perform: (File) -> Result<Unit>,
    /**
     * 参数依次为文件、终态回调、面板有效性检查、入队通知。
     * 只有宿主确认原生入队后才调用第 4 个参数（面板随即关闭）；终态仍单独回调。
     */
    val performAsync: ((File, (Result<Unit>) -> Unit, () -> Boolean, () -> Unit) -> Unit)? = null,
)

/**
 * 自定义图片面板：浏览本地文件夹，或从聊天保存图片。
 */
class SaveImagePanel private constructor(
    private val context: Context,
    private val imageUrls: List<String>,
    private val actions: List<ImagePanelAction>,
    initialActionId: String?,
    private val onActionSelected: ((String) -> Unit)?,
) {
    private val colors = PanelColors.from(context)
    private val pending = arrayOfNulls<ImageDownloader.DownloadedImage>(1)
    private val selected = arrayOfNulls<ImageEntry>(1)
    private lateinit var titleView: TextView
    private lateinit var folderRow: LinearLayout
    /** 浏览模式的分类侧栏：只创建/绑定可见分类，避免一次建满 20+ 张卡片和封面任务。 */
    private var folderAdapter: FolderAdapter? = null
    private var folderListView: ListView? = null
    private var folderListMessage: TextView? = null
    /** 分类栏数据版本：只在分类/标签/可删性变化时让可见卡片重建；选中态就地刷新。 */
    private var folderDatasetVersion = 0

    /** 上次扫描到的分类列表，切换分类时复用，不重复扫盘。 */
    private var loadedFolders: List<ImageEntry> = emptyList()
    /**
     * 本次目录快照：分类元数据、可删除性、已枚举分类的封面，以及首屏唯一一次图片复用。
     * 面板存续期间切回分类仍走 ImageFolderStore.images() 重新枚举，不把快照当目录缓存。
     */
    private var currentScan: FolderScan? = null
    /** 分类显示名与可删除能力在后台一次算好，主线程不再访问 provider。 */
    private var folderLabels: Map<String, String> = emptyMap()
    private var deletableFolders: Set<String> = emptySet()
    /** 授权根是否能新建分类；来自后台查询，未知时禁用新建按钮。 */
    private var rootWritable = false
    private var imageGrid: GridView? = null
    private var emptyHint: TextView? = null
    private var previewView: ImageView? = null
    private var previewProgress: ProgressBar? = null
    private var panelDialog: Dialog? = null
    private var deleteFolderButton: ImageButton? = null
    private var createFolderButton: ImageButton? = null
    private var saveButton: TextView? = null
    private var previewDialog: Dialog? = null
    private var performingAction = false
    /** 面板关闭会移除 View，异步回调统一投递到主线程 Looper 而不是具体 View。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastAccessErrorAt = 0L
    private var selectedAction = actions.firstOrNull { it.id == initialActionId }
        ?: actions.firstOrNull()
    private var actionButton: ImageButton? = null
    private val thumbnailExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "QQZygisk-ImageThumbnail").apply { isDaemon = true }
    }
    private val folderCoverExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "QQZygisk-FolderCover").apply { isDaemon = true }
    }
    /** 首图贴出前挂起的可见分类封面请求；只在主线程读写。 */
    private val pendingFolderCovers = mutableListOf<() -> Unit>()
    @Volatile
    private var folderCoverGateOpen = false
    /** 目录枚举、文档复制、保存/删除等磁盘与 provider 访问统一走这里，绝不在主线程做。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "QQZygisk-ImageIO").apply { isDaemon = true }
    }
    @Volatile
    private var folderGeneration = 0
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    private val animatedThumbnails = object : LruCache<String, Drawable>(64) {}
    private val thumbnailRequests = ConcurrentHashMap.newKeySet<String>()
    private var thumbnailRefreshPosted = false
    private var thumbnailRefreshGeneration = 0
    @Volatile
    private var imageGeneration = 0
    /**
     * 面板生命周期内最近访问分类的真实图片列表：访问序 LRU，dismiss 时清空，无 TTL。
     * 只保存 Store 返回的不可变列表，不把它当目录缓存：每次点击仍会后台静默重读。
     */
    private val imageListCache = object : LinkedHashMap<String, List<ImageEntry>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<ImageEntry>>,
        ): Boolean = size > IMAGE_LIST_CACHE_SIZE
    }
    /** 当前网格 adapter 对应的分类与内容签名；签名相同则不替换 adapter，保留滚动位置。 */
    private var imageListFolderKey: String? = null
    private var imageListSignature: Long? = null
    /** 当前网格实际显示的列表；LRU 淘汰后点击仍显示中的分类不回退到加载态。 */
    private var imageListCurrent: List<ImageEntry> = emptyList()
    /** 图片列表请求序号：切换分类/重新绑定后，旧的静默刷新结果直接丢弃。 */
    private var imageListRequestId = 0
    /**
     * browseOnly 首屏只按已保存选择提前绑定真实分类列表；完整目录回来时据此决定
     * 是接续同一个 adapter（不闪烁），还是用户已改选后按当前选择重新枚举。
     */
    private var earlyBindingGeneration = -1
    private var earlyBindingKey: String? = null
    private var earlyBindingImageGeneration = -1
    /** 本次扫描入口的选择与图片代：期间改选/重绑后，完整回填不能拿旧缓存覆盖。 */
    private var bindEntrySelectionKey: String? = null
    private var bindEntryImageGeneration = -1
    @Volatile
    private var closed = false
    private val browseOnly get() = imageUrls.isEmpty()

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        panelDialog = dialog
        dialog.setContentView(buildContent(dialog))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            closed = true
            imageGeneration++
            folderGeneration++
            imageListRequestId++
            imageListFolderKey = null
            imageListSignature = null
            imageListCurrent = emptyList()
            imageListCache.clear()
            thumbnailExecutor.shutdownNow()
            folderCoverExecutor.shutdownNow()
            ioExecutor.shutdownNow()
            thumbnailRequests.clear()
            pendingFolderCovers.clear()
            synchronized(thumbnailCache) { thumbnailCache.evictAll() }
            synchronized(animatedThumbnails) { animatedThumbnails.evictAll() }
            previewDialog?.dismiss()
            previewDialog = null
            panelDialog = null
        }
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.4f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
        bindFolders()
        if (!browseOnly) loadPreview()
    }

    private fun buildContent(dialog: Dialog): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colors.surface, topRadius = context.dp(24).toFloat())
            val pad = context.dp(PANEL_HORIZONTAL_PADDING)
            setPadding(pad, context.dp(10), pad, context.dp(24))
        }

        root.addView(
            View(context).apply {
                background = rounded(colors.handle, context.dp(2).toFloat())
            },
            LinearLayout.LayoutParams(context.dp(36), context.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = context.dp(16)
            },
        )

        titleView = TextView(context).apply {
            text = if (browseOnly) "图片面板" else "保存图片"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                titleView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(8)
                },
            )
            if (browseOnly && actions.size > 1) {
                addView(
                    createActionMenuButton(),
                    LinearLayout.LayoutParams(context.dp(48), context.dp(48)),
                )
            }
            addView(
                deleteFolderButton(),
                LinearLayout.LayoutParams(context.dp(48), context.dp(48)),
            )
            addView(
                createFolderButton(),
                LinearLayout.LayoutParams(context.dp(48), context.dp(48)),
            )
        }
        root.addView(
            titleRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.dp(16) },
        )

        if (!browseOnly) {
            previewView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(colors.preview, context.dp(20).toFloat())
                clipToOutline = true
                outlineProvider = roundedOutline(context.dp(20).toFloat())
            }
            previewProgress = ProgressBar(context)
            val previewBox = FrameLayout(context).apply {
                addView(
                    previewView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(200)),
                )
                addView(
                    previewProgress,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            root.addView(
                previewBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(200),
                ).apply { bottomMargin = context.dp(20) },
            )
        }

        folderRow = LinearLayout(context).apply {
            orientation = if (browseOnly) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (browseOnly) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        }

        if (browseOnly) {
            emptyHint = TextView(context).apply {
                text = "这个文件夹还没有图片"
                setTextColor(colors.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(0, context.dp(24), 0, context.dp(24))
            }
            imageGrid = GridView(context).apply {
                numColumns = IMAGE_COLUMNS
                stretchMode = GridView.NO_STRETCH
                gravity = Gravity.START
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
                    (parent.adapter.getItem(position) as? ImageEntry)?.let(::performAction)
                }
                onItemLongClickListener = AdapterView.OnItemLongClickListener { parent, _, position, _ ->
                    val entry = parent.adapter.getItem(position) as? ImageEntry
                        ?: return@OnItemLongClickListener false
                    showEmoticonPreview(entry)
                    true
                }
            }
            val gridBox = FrameLayout(context).apply {
                addView(
                    imageGrid,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    emptyHint,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
            val split = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val list = ListView(context).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                divider = ColorDrawable(Color.TRANSPARENT)
                dividerHeight = context.dp(8)
                setPadding(0, 0, 0, 0)
                folderListView = this
                adapter = FolderAdapter().also { folderAdapter = it }
            }
            val listMessage = messageView("").apply {
                visibility = View.GONE
                setPadding(context.dp(4), context.dp(12), context.dp(4), context.dp(12))
            }
            folderListMessage = listMessage
            val folderColumn = FrameLayout(context)
            folderColumn.addView(
                list,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            folderColumn.addView(
                listMessage,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
            split.addView(
                folderColumn,
                LinearLayout.LayoutParams(
                    context.dp(FOLDER_COLUMN_WIDTH),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    marginEnd = context.dp(FOLDER_GRID_GAP)
                },
            )
            split.addView(
                gridBox,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            root.addView(
                split,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        } else {
            root.addView(
                TextView(context).apply {
                    text = "选择文件夹"
                    setTextColor(colors.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = context.dp(10) },
            )
            root.addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    addView(
                        folderRow,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = context.dp(20) },
            )
        }

        if (!browseOnly) {
            root.addView(
                filledButton("保存", colors.primary, colors.onPrimary) {
                    saveCurrent()
                }.also {
                    saveButton = it
                    it.isEnabled = false
                    it.alpha = 0.4f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(48),
                ),
            )
        }

        val panelHeight = if (browseOnly) {
            (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        return FrameLayout(context).apply {
            setOnClickListener { dialog.dismiss() }
            addView(
                root.apply { setOnClickListener { } },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelHeight,
                    Gravity.BOTTOM,
                ),
            )
        }
    }

    private fun bindFolders() {
        val generation = ++folderGeneration
        val includeExternal = browseOnly
        val preferredKey = selected[0]?.key
        // 预热尺寸在主线程入口先预测好，worker 不读取 View 尺寸；此时通常未布局，取面板宽度估算。
        val prefetchTarget = imageGrid?.let { grid -> grid to cellSizeFor(grid) }
        clearEarlyBinding()
        // 记录入口选择与图片代：早期回调落主线程时只有用户仍停在这一选择才允许绑定，
        // 完整回填也据此判断用户是否已改选/重绑，避免切分类后闪回旧列表。
        val entrySelectionKey = preferredKey
        bindEntrySelectionKey = preferredKey
        bindEntryImageGeneration = imageGeneration
        val submit = runCatching {
            ioExecutor.execute {
                var earlySelectedKey: String? = null
                // 仅浏览模式接受普通 File 来源的早期列表；SAF/混合来源由 Store 走完整流程，不回调。
                val onInitialLocalImages: ((ImageEntry, List<ImageEntry>) -> Unit)? =
                    if (includeExternal) {
                        { folder, images ->
                            earlySelectedKey = folder.key
                            // 首图预热直接从 IO 任务发出：与主线程首布局/GPU 初始化并行，不等面板绑定。
                            prefetchTarget?.let { (grid, size) ->
                                images.firstOrNull()?.let { first ->
                                    prefetchFirstThumbnail(first, grid, generation, size)
                                }
                            }
                            postToPanel {
                                if (generation != folderGeneration) return@postToPanel
                                if (imageGeneration != bindEntryImageGeneration) return@postToPanel
                                if (selected[0]?.key != entrySelectionKey) return@postToPanel
                                bindInitialLocalFolder(generation, folder, images)
                            }
                        }
                    } else {
                        null
                    }
                val snapshot = runCatching {
                    val scan = ImageFolderStore.scanFolders(
                        includeExternal = includeExternal,
                        initialSelectionKey = preferredKey.takeIf { includeExternal },
                        onInitialLocalImages = onInitialLocalImages,
                    )
                    val folders = scan.folders
                    val lastKey = runCatching {
                        ImageFolderStore.lastFolderFrom(folders, scan.root)
                    }.getOrNull()?.key
                    // 与早期回调和 onFoldersLoaded 的选中规则完全一致：刷新时保留当前分类。
                    val target = folders.firstOrNull { it.key == earlySelectedKey }
                        ?: folders.firstOrNull { it.key == preferredKey }
                        ?: folders.firstOrNull { it.key == lastKey }
                        ?: folders.firstOrNull { !ImageFolderStore.isHistoryFolder(it) }
                        ?: folders.firstOrNull()
                    // 只补目标分类；其余自有分类保持根枚举元数据，不提前扫内容。
                    val readyScan = target?.let { folder ->
                        runCatching { scan.withImagesFor(folder) }
                            .onFailure {
                                Log.error("首屏分类图片读取失败: ${folder.key}", it)
                            }
                            .getOrDefault(scan)
                    } ?: scan
                    // 首图预热直接从 IO 任务发出：与主线程首布局/GPU 初始化并行，不等面板绑定。
                    prefetchTarget?.let { (grid, size) ->
                        target?.let { folder ->
                            readyScan.imagesByFolder[folder.key]?.firstOrNull()?.let { first ->
                                prefetchFirstThumbnail(first, grid, generation, size)
                            }
                        }
                    }
                    FolderSnapshot(
                        scan = readyScan,
                        // 显示名随本次扫描的分类元数据一次捕获，主线程不再逐项查询 provider。
                        labels = scan.labels,
                        // 可删除性直接来自本次扫描的根与分类父子关系，不再为每个分类解析授权根。
                        deletable = folders.filter(scan::canDelete).map { it.key }.toSet(),
                        lastKey = lastKey,
                        rootWritable = scan.root.canWrite,
                    )
                }
                postToPanel {
                    if (generation != folderGeneration) return@postToPanel
                    snapshot.fold(
                        onSuccess = ::onFoldersLoaded,
                        onFailure = ::onFoldersFailed,
                    )
                }
            }
        }
        submit.onFailure {
            Log.error("无法调度目录读取", it)
            onFoldersFailed(it)
        }
    }

    private fun onFoldersLoaded(snapshot: FolderSnapshot) {
        val folders = snapshot.scan.folders
        // 早期列表只在用户没改选、也没发生新图片绑定时才可接续；否则回填会覆盖较新的列表。
        val earlyContinued = earlyBindingGeneration == folderGeneration &&
            earlyBindingKey != null &&
            earlyBindingKey == selected[0]?.key &&
            earlyBindingImageGeneration == imageGeneration
        val earlyBindingAttempted = earlyBindingGeneration == folderGeneration
        // 入口之后用户改选或发生过新的图片绑定：完整回填不得拿旧快照覆盖较新的列表。
        val userMovedOn = imageGeneration != bindEntryImageGeneration ||
            (bindEntrySelectionKey != null && selected[0]?.key != bindEntrySelectionKey)
        loadedFolders = folders
        currentScan = snapshot.scan
        folderLabels = snapshot.labels
        deletableFolders = snapshot.deletable
        rootWritable = snapshot.rootWritable
        if (folders.isEmpty()) {
            clearEarlyBinding()
            selected[0] = null
            if (browseOnly) titleView.text = "图片面板"
            showFolderMessage("还没有文件夹")
            refreshFolderActions()
            bindImages(null)
            return
        }
        val selectionChanged = selected[0] == null || folders.none { it.key == selected[0]?.key }
        if (selectionChanged) {
            selected[0] = folders.firstOrNull { it.key == snapshot.lastKey }
                ?: folders.firstOrNull { !ImageFolderStore.isHistoryFolder(it) }
                ?: folders.firstOrNull()
        }
        if (earlyContinued && !selectionChanged) {
            // 同一个分类、同一次绑定的真实列表：保留 adapter，只补完整分类栏、标签与权限。
            renderFolderCards()
            clearEarlyBinding()
            return
        }
        if (earlyBindingAttempted || (browseOnly && userMovedOn)) {
            // 用户已切分类或图片代已变化：按当前选择重新枚举，不拿早期缓存回滚较新的列表。
            bindImages(selected[0])
        } else {
            // 没有早期回调（SAF/无有效选择）时，复用一次本次扫描刚取到的目标分类图片；
            // 此后切回分类一律重新枚举。
            val initial = selected[0]?.let { currentScan?.imagesByFolder?.get(it.key) }
            bindImages(selected[0], initialImages = initial)
        }
        renderFolderCards()
        clearEarlyBinding()
    }

    /**
     * browseOnly 首屏的早期绑定：Store 在完整目录发现前就交出了已保存分类的真实可读列表。
     * 只更新真实选中、标题与网格；分类栏、标签与可删性等完整快照回来后再补。
     */
    private fun bindInitialLocalFolder(
        generation: Int,
        folder: ImageEntry,
        images: List<ImageEntry>,
    ) {
        if (closed || generation != folderGeneration) return
        selected[0] = folder
        earlyBindingGeneration = generation
        earlyBindingKey = folder.key
        titleView.text = folder.name
        // 完整目录未到前不把单个分类当成 loadedFolders；删除/新建先禁用，避免对未验证目录做变更。
        deletableFolders = emptySet()
        rootWritable = false
        refreshFolderActions()
        bindImages(folder, initialImages = images)
        earlyBindingImageGeneration = imageGeneration
    }

    private fun clearEarlyBinding() {
        earlyBindingGeneration = -1
        earlyBindingKey = null
        earlyBindingImageGeneration = -1
    }

    private fun onFoldersFailed(error: Throwable) {
        Log.error("读取图片目录失败", error)
        clearEarlyBinding()
        loadedFolders = emptyList()
        currentScan = null
        folderLabels = emptyMap()
        deletableFolders = emptySet()
        rootWritable = false
        selected[0] = null
        if (browseOnly) titleView.text = "图片面板"
        val message = panelErrorMessage(error)
        showFolderMessage(message)
        refreshFolderActions()
        bindImages(null, message)
    }

    private fun folderLabel(folder: ImageEntry): String =
        folderLabels[folder.key] ?: folder.name

    /** 授权或 provider 失效时必须明确提示重新授权，不能表现成空目录。 */
    private fun panelErrorMessage(error: Throwable): String {
        if (error is ImageAccessException) {
            return error.message?.takeIf { it.isNotBlank() }
                ?: "图片目录授权已失效，请在模块设置中重新选择并授权该文件夹"
        }
        if (error is SecurityException || error is java.io.FileNotFoundException) {
            return "图片目录授权已失效，请在模块设置中重新选择并授权该文件夹"
        }
        val detail = error.message?.takeIf { it.isNotBlank() }
        return if (detail == null) {
            "无法读取图片目录，请在模块设置中重新授权该文件夹"
        } else {
            "无法读取图片目录：$detail（可在模块设置中重新授权）"
        }
    }

    private fun messageView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(colors.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(context.dp(4), context.dp(12), context.dp(4), context.dp(12))
    }

    private fun postToPanel(action: () -> Unit) {
        titleView.post {
            if (!closed) action()
        }
    }

    /** 磁盘与 provider 访问在 IO 线程执行，结果只回传仍存活的当前面板。 */
    private fun <T> submitIo(
        label: String,
        onSuccess: (T) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        block: () -> T,
    ) {
        val submit = runCatching {
            ioExecutor.execute {
                val result = runCatching { block() }
                result.onFailure { Log.error("$label 失败", it) }
                postToPanel {
                    result.fold(onSuccess = onSuccess, onFailure = onFailure)
                }
            }
        }
        submit.onFailure { error ->
            Log.error("无法调度$label", error)
            onFailure(error)
        }
    }

    /** 浏览模式侧栏：只为可见位置创建卡片，滚动时按数据版本复用。 */
    private inner class FolderAdapter : BaseAdapter() {
        var folders: List<ImageEntry> = emptyList()

        override fun getCount(): Int = folders.size

        override fun getItem(position: Int): ImageEntry = folders[position]

        override fun getItemId(position: Int): Long = folders[position].key.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val folder = getItem(position)
            // 选中态不属于卡片结构：stamp 只覆盖分类/标签/可删性，复用后原地刷新即可。
            val stamp = "${folderDatasetVersion}|${folder.key}|${folderLabel(folder)}|" +
                (folder.key in deletableFolders)
            val view = (convertView as? LinearLayout)?.takeIf { it.tag == stamp }
                ?: folderCard(folder).apply { tag = stamp }
            applyFolderCardSelection(view, folder.key == selected[0]?.key)
            view.layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return view
        }
    }

    /**
     * 只重建分类栏，不重新遍历文件系统（切换分类时用，避免整盘重扫导致卡顿）。
     * 浏览模式由 ListView 按可见位置懒绑定；普通保存模式仍是原有的横向行。
     */
    private fun renderFolderCards() {
        if (browseOnly) {
            folderDatasetVersion++
            folderAdapter?.let { adapter ->
                adapter.folders = loadedFolders
                adapter.notifyDataSetChanged()
            }
            folderListMessage?.visibility = View.GONE
            titleView.text = selected[0]?.let(::folderLabel) ?: "图片面板"
            refreshFolderActions()
            return
        }
        folderRow.removeAllViews()
        loadedFolders.forEach { folder ->
            folderRow.addView(folderCard(folder))
        }
        refreshFolderActions()
    }

    /** 空列表/目录错误时在分类栏内提示；浏览模式同时清空懒加载列表，避免残留旧卡片。 */
    private fun showFolderMessage(text: String?) {
        if (browseOnly) {
            folderDatasetVersion++
            folderAdapter?.let { adapter ->
                adapter.folders = emptyList()
                adapter.notifyDataSetChanged()
            }
            folderListMessage?.let { message ->
                message.text = text.orEmpty()
                message.visibility = if (text == null) View.GONE else View.VISIBLE
            }
            return
        }
        folderRow.removeAllViews()
        if (text != null) folderRow.addView(messageView(text))
    }

    private fun folderCard(folder: ImageEntry): View {
        val checked = folder.key == selected[0]?.key
        val size = if (browseOnly) context.dp(40) else context.dp(56)
        val displayName = folderLabel(folder)
        val image = ImageView(context).apply {
            contentDescription = displayName
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(colors.preview, context.dp(16).toFloat())
            clipToOutline = true
            outlineProvider = roundedOutline(context.dp(16).toFloat())
            setImageResource(
                if (ImageFolderStore.isHistoryFolder(folder)) {
                    R.drawable.ic_history
                } else {
                    R.drawable.ic_save
                },
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(colors.muted)
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        }
        if (!ImageFolderStore.isHistoryFolder(folder)) {
            loadFolderCover(folder, size, image)
        }
        return LinearLayout(context).apply {
            contentDescription = displayName
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = folderCardBackground(checked)
            val padH = context.dp(8)
            val padV = context.dp(6)
            setPadding(padH, padV, padH, padV)
            addView(image, LinearLayout.LayoutParams(size, size))
            if (!browseOnly) {
                addView(
                    TextView(context).apply {
                        text = displayName
                        setTextColor(if (checked) colors.primary else colors.onSurface)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(
                        size + context.dp(8),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = context.dp(6)
                    },
                )
            }
            if (browseOnly) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = context.dp(8) }
            }
            setOnClickListener { selectFolder(folder) }
            if (folder.key in deletableFolders) {
                isLongClickable = true
                setOnLongClickListener {
                    confirmDeleteFolder(folder)
                    true
                }
            }
        }
    }

    /** 点击分类：侧栏只就地更新标题和选中态，不递增版本、不重建可见卡片；网格走缓存+静默重读。 */
    private fun selectFolder(folder: ImageEntry) {
        selected[0] = folder
        submitIo("记住上次分类") { ImageFolderStore.remember(folder) }
        if (browseOnly) {
            titleView.text = folderLabel(folder)
            refreshFolderActions()
            refreshFolderSelection()
        } else {
            renderFolderCards()
        }
        bindImages(folder)
    }

    /** 只改可见卡片的背景与文字颜色，用于侧栏选中态变化，不触发卡片重建。 */
    private fun refreshFolderSelection() {
        if (!browseOnly) return
        val list = folderListView ?: return
        val adapter = folderAdapter ?: return
        val checkedKey = selected[0]?.key
        for (index in 0 until list.childCount) {
            val card = list.getChildAt(index) ?: continue
            val position = list.getPositionForView(card)
            val folder = adapter.folders.getOrNull(position) ?: continue
            applyFolderCardSelection(card, folder.key == checkedKey)
        }
    }

    private fun applyFolderCardSelection(card: View, checked: Boolean) {
        card.background = folderCardBackground(checked)
        val group = card as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            val label = group.getChildAt(index) as? TextView ?: continue
            label.setTextColor(if (checked) colors.primary else colors.onSurface)
        }
    }

    private fun folderCardBackground(checked: Boolean): Drawable =
        if (checked) {
            rounded(colors.selected, context.dp(18).toFloat(), colors.primary, context.dp(2))
        } else {
            Color.TRANSPARENT.toDrawable()
        }

    private fun cachedImagesFor(folderKey: String): List<ImageEntry>? = imageListCache[folderKey]

    private fun rememberImages(folderKey: String, files: List<ImageEntry>) {
        imageListCache[folderKey] = files
    }

    /** 只按 key/modified/size 判断列表是否变化；签名相同就不替换 adapter，保留滚动位置。 */
    private fun imageListSignatureOf(files: List<ImageEntry>): Long {
        var hash = 1125899906842597L
        for (entry in files) {
            hash = 31 * hash + entry.key.hashCode()
            hash = 31 * hash + entry.modified
            hash = 31 * hash + entry.size
        }
        return 31 * hash + files.size
    }

    /**
     * [initialImages] 只给扫描完成后的首次绑定用：直接复用本次快照里刚取到的图片，
     * 避免“刚扫完又排队扫一遍”。普通切换分类必须传 null：先同步显示面板生命周期内
     * 最近访问过的真实列表，再由 [refreshImagesSilently]/[loadImages] 后台重新枚举。
     */
    private fun bindImages(
        folder: ImageEntry?,
        errorText: String? = null,
        initialImages: List<ImageEntry>? = null,
    ) {
        val grid = imageGrid ?: return
        val hint = emptyHint ?: return
        // 任何重新绑定都让旧的静默刷新结果失效，避免旧列表回写覆盖当前分类。
        imageListRequestId++
        if (folder == null) {
            imageGeneration++
            imageListFolderKey = null
            imageListSignature = null
            imageListCurrent = emptyList()
            grid.adapter = null
            hint.text = errorText ?: "还没有文件夹"
            hint.visibility = View.VISIBLE
            return
        }
        if (initialImages != null) {
            applyImages(grid, hint, folder, initialImages)
            return
        }
        val cached = cachedImagesFor(folder.key)
            ?: imageListCurrent.takeIf { imageListFolderKey == folder.key }
        if (cached != null) {
            // 曾访问过：不置空 adapter、不显示加载文案，先同步显示旧列表，再后台静默重读。
            applyImages(grid, hint, folder, cached)
            refreshImagesSilently(folder)
            return
        }
        // 首次访问且没有缓存：保持“正在加载…”，等后台真实列表。
        val generation = ++imageGeneration
        val requestId = imageListRequestId
        imageListFolderKey = null
        imageListSignature = null
        imageListCurrent = emptyList()
        grid.adapter = null
        hint.text = "正在加载…"
        hint.visibility = View.VISIBLE
        loadImages(folder, generation, requestId)
    }

    /** 首次访问分类：后台读取真实列表；失败才显示明确错误，已有内容不动。 */
    private fun loadImages(folder: ImageEntry, generation: Int, requestId: Int) {
        val grid = imageGrid ?: return
        val hint = emptyHint ?: return
        val submit = runCatching {
            ioExecutor.execute {
                val result = runCatching { ImageFolderStore.images(folder) }
                postToPanel {
                    if (requestId != imageListRequestId) return@postToPanel
                    if (generation != imageGeneration) return@postToPanel
                    if (selected[0]?.key != folder.key) return@postToPanel
                    result.fold(
                        onSuccess = { files -> applyImages(grid, hint, folder, files) },
                        onFailure = { error ->
                            Log.error("读取分类图片失败: ${folder.key}", error)
                            hint.text = panelErrorMessage(error)
                            hint.visibility = View.VISIBLE
                        },
                    )
                }
            }
        }
        submit.onFailure {
            Log.error("无法调度分类图片读取", it)
            hint.text = panelErrorMessage(it)
            hint.visibility = View.VISIBLE
        }
    }

    /**
     * 静默重读已显示分类：不递增 imageGeneration（否则旧的在途解码会被永久判为过期），
     * 只在回调确认分类仍选中、请求仍是最新一次后才应用；签名变化才替换 adapter。
     */
    private fun refreshImagesSilently(folder: ImageEntry) {
        val grid = imageGrid ?: return
        val hint = emptyHint ?: return
        val generation = imageGeneration
        val requestId = ++imageListRequestId
        val submit = runCatching {
            ioExecutor.execute {
                val result = runCatching { ImageFolderStore.images(folder) }
                postToPanel {
                    if (requestId != imageListRequestId) return@postToPanel
                    if (generation != imageGeneration) return@postToPanel
                    if (selected[0]?.key != folder.key) return@postToPanel
                    result.fold(
                        onSuccess = { files -> applyImages(grid, hint, folder, files) },
                        onFailure = { error ->
                            Log.error("刷新分类图片失败: ${folder.key}", error)
                            val message = panelErrorMessage(error)
                            if (imageListFolderKey == folder.key && imageListCurrent.isNotEmpty()) {
                                // 已有内容必须保留，只给出明确错误。
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            } else {
                                hint.text = message
                                hint.visibility = View.VISIBLE
                            }
                        },
                    )
                }
            }
        }
        submit.onFailure {
            Log.error("无法调度分类图片刷新", it)
            if (imageListFolderKey == folder.key && imageListCurrent.isNotEmpty()) {
                Toast.makeText(context, panelErrorMessage(it), Toast.LENGTH_LONG).show()
            } else {
                hint.text = panelErrorMessage(it)
                hint.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 应用一组真实图片列表：key/modified/size 签名相同就保留当前 adapter 和滚动位置；
     * 只有签名变化（或首次绑定）才递增 imageGeneration 并替换 adapter。
     */
    private fun applyImages(
        grid: GridView,
        hint: TextView,
        folder: ImageEntry,
        files: List<ImageEntry>,
    ) {
        val signature = imageListSignatureOf(files)
        rememberImages(folder.key, files)
        imageListCurrent = files
        val sameList = imageListFolderKey == folder.key &&
            imageListSignature == signature &&
            (grid.adapter != null || files.isEmpty())
        if (sameList) {
            hint.text = "这个文件夹还没有图片"
            hint.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            if (files.isEmpty()) {
                // 没有可等首图：分类封面可以立即补上。
                openFolderCoverGate()
            }
            return
        }
        imageListFolderKey = folder.key
        imageListSignature = signature
        val generation = ++imageGeneration
        hint.text = "这个文件夹还没有图片"
        if (files.isEmpty()) {
            grid.adapter = null
            hint.visibility = View.VISIBLE
            // 没有可等首图：分类封面可以立即补上。
            openFolderCoverGate()
            return
        }
        hint.visibility = View.GONE
        // 列表一到就与 UI 布局并行解码首张；后台已预热的同 key 请求会直接去重。
        prefetchFirstThumbnail(files.first(), grid, folderGeneration, cellSizeFor(grid))
        val fill = fill@{
            if (closed || generation != imageGeneration) return@fill
            // 布局前可能又切了分类：只安装仍对应当前分类与签名的列表。
            if (imageListFolderKey != folder.key || imageListSignature != signature) return@fill
            val gap = context.dp(IMAGE_GAP)
            val cell = cellSizeFor(grid)
            grid.columnWidth = cell
            grid.horizontalSpacing = gap
            grid.verticalSpacing = gap
            grid.adapter = ImageGridAdapter(files, cell, generation)
        }
        if (grid.width > context.dp(120)) fill() else grid.post { fill() }
    }

    /** 网格单元格边长：优先实际已知的 pane 宽度，布局前与 adapter 共用同一套面板宽度估算。 */
    private fun cellSizeFor(grid: GridView): Int {
        val gap = context.dp(IMAGE_GAP)
        val paneWidth = grid.width
            .takeIf { it > context.dp(120) }
            ?: (
                context.resources.displayMetrics.widthPixels -
                    context.dp(
                        PANEL_HORIZONTAL_PADDING * 2 +
                            FOLDER_COLUMN_WIDTH +
                            FOLDER_GRID_GAP,
                    )
                )
        return ((paneWidth - gap * (IMAGE_COLUMNS - 1)) / IMAGE_COLUMNS)
            .coerceAtLeast(context.dp(48))
    }

    private inner class ImageGridAdapter(
        private val files: List<ImageEntry>,
        private val cellSize: Int,
        private val generation: Int,
    ) : BaseAdapter() {
        override fun getCount(): Int = files.size

        override fun getItem(position: Int): ImageEntry = files[position]

        override fun getItemId(position: Int): Long = files[position].key.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = getItem(position)
            val image = (convertView as? ImageView) ?: ImageView(context).apply {
                // Recycled cells must retain GridView's forceAdd and other layout metadata.
                layoutParams = AbsListView.LayoutParams(cellSize, cellSize)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(colors.preview, context.dp(12).toFloat())
                clipToOutline = true
                outlineProvider = roundedOutline(context.dp(12).toFloat())
                setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            }
            image.contentDescription = "发送 ${entry.name}"
            loadImageThumbnail(entry, cellSize, image, generation)
            return image
        }
    }

    private fun loadImageThumbnail(
        entry: ImageEntry,
        size: Int,
        image: ImageView,
        generation: Int,
    ) {
        val key = thumbnailKey(entry, size)
        if (image.tag == key && image.drawable != null) {
            AnimatedImageLoader.bind(image, image.drawable)
            return
        }
        val sameKey = image.tag == key
        image.tag = key
        synchronized(thumbnailCache) { thumbnailCache.get(key) }?.let {
            if (!sameKey || image.drawable == null) {
                AnimatedImageLoader.clear(image)
                image.setImageBitmap(it)
            }
            openFolderCoverGate()
            return
        }
        synchronized(animatedThumbnails) { animatedThumbnails.get(key) }?.let { cached ->
            AnimatedImageLoader.bind(image, cached)
            openFolderCoverGate()
            return
        }
        if (!sameKey) {
            AnimatedImageLoader.clear(image)
        }
        // 首图预热在途时不再排第二次解码；预热完成会贴到本格或触发一次刷新命中缓存。
        if (thumbnailRequests.contains(PREFETCH_REQUEST_PREFIX + key)) return
        val requestKey = "$generation:$key"
        if (!thumbnailRequests.add(requestKey)) return
        runCatching {
            thumbnailExecutor.execute {
                var published = false
                try {
                    if (closed || generation != imageGeneration) return@execute
                    val drawable = runCatching { decodeEntry(entry, size) }
                        .onFailure { error ->
                            Log.warn("解码图片失败: ${entry.key}", error)
                            reportDecodeAccessError(error)
                        }
                        .getOrNull() ?: return@execute
                    if (closed || generation != imageGeneration) return@execute
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        synchronized(thumbnailCache) { thumbnailCache.put(key, bitmap) }
                    } else {
                        synchronized(animatedThumbnails) { animatedThumbnails.put(key, drawable) }
                    }
                    publishThumbnail(key, drawable, bitmap, generation)
                    published = true
                } finally {
                    thumbnailRequests.remove(requestKey)
                    // 首图解码失败或过期时不能让封面永远等下去：本代请求清空后补发封面。
                    if (!published && !folderCoverGateOpen) {
                        imageGrid?.post {
                            if (
                                !closed &&
                                generation == imageGeneration &&
                                thumbnailRequests.none {
                                    it.startsWith("$generation:") ||
                                        it.startsWith(PREFETCH_REQUEST_PREFIX)
                                }
                            ) {
                                openFolderCoverGate()
                            }
                        }
                    }
                }
            }
        }.onFailure {
            thumbnailRequests.remove(requestKey)
            Log.error("[ImageAnim] thumbnail-request-failed generation=$generation key=$key", it)
        }
    }


    /**
     * 本次列表刚到手时先解码首张，和主线程首布局/GPU 初始化并行。
     *
     * 三个入口：Store 的本地早期回调（完整目录发现前）、bindFolders 的 IO 任务
     * （SAF/无有效选择时）都在拿到目标分类图片后立即调用（主线程入口已预测好 cell），
     * 以及 applyImages 对普通切换/回退补一次；同 key 的 prefetch: 请求键保证只排一次解码，
     * getView 遇到同一张图在途时也不再排第二次。
     * 过期取消只看 folderGeneration/closed；imageGeneration 在首次绑定前可能尚未递增，
     * 因此解码前不做代数门禁，完成后的贴图/刷新一律在主线程按当时的 imageGeneration 判定。
     * 尺寸估算与 adapter 不一致时预热结果只是不命中缓存键，正常请求会按实际 cell 继续。
     */
    private fun prefetchFirstThumbnail(
        entry: ImageEntry,
        grid: GridView,
        folderGen: Int,
        size: Int,
    ) {
        if (closed) return
        val key = thumbnailKey(entry, size)
        synchronized(thumbnailCache) { if (thumbnailCache.get(key) != null) return }
        synchronized(animatedThumbnails) { if (animatedThumbnails.get(key) != null) return }
        val requestKey = PREFETCH_REQUEST_PREFIX + key
        if (!thumbnailRequests.add(requestKey)) return
        runCatching {
            thumbnailExecutor.execute {
                var cached = false
                try {
                    if (closed || folderGen != folderGeneration) return@execute
                    val drawable = runCatching { decodeEntry(entry, size) }
                        .onFailure { error ->
                            Log.warn("预解码首图失败: ${entry.key}", error)
                            reportDecodeAccessError(error)
                        }
                        .getOrNull() ?: return@execute
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        synchronized(thumbnailCache) { thumbnailCache.put(key, bitmap) }
                    } else {
                        synchronized(animatedThumbnails) { animatedThumbnails.put(key, drawable) }
                    }
                    cached = true
                    // 解码期间用户可能已切分类：按主线程此刻的代数贴图或刷新，
                    // 否则新绑定的 getView 会被同 key 去重后永远等不到结果。
                    grid.post {
                        if (closed) return@post
                        if (folderGen == folderGeneration) {
                            applyThumbnail(grid, key, drawable, bitmap, imageGeneration)
                        } else {
                            // 去重键跨扫描代共享；旧任务结束也必须唤醒依赖它的新一代格子。
                            requestThumbnailRefresh(grid, imageGeneration)
                        }
                    }
                } finally {
                    thumbnailRequests.remove(requestKey)
                    // 失败或过期后让当前格重新 getView：命中缓存就直接贴，否则回到正常请求。
                    if (!cached) {
                        grid.post {
                            if (closed) return@post
                            val current = imageGeneration
                            requestThumbnailRefresh(grid, current)
                            // 首图确实拿不到时不能把分类封面永远挡在闸门外。
                            if (!folderCoverGateOpen && thumbnailRequests.none {
                                    it.startsWith("$current:") ||
                                        it.startsWith(PREFETCH_REQUEST_PREFIX)
                                }
                            ) {
                                openFolderCoverGate()
                            }
                        }
                    }
                }
            }
        }.onFailure {
            thumbnailRequests.remove(requestKey)
            Log.error("无法调度首图预解码: ${entry.key}", it)
        }
    }

    private fun publishThumbnail(
        key: String,
        drawable: Drawable,
        bitmap: Bitmap?,
        generation: Int,
    ) {
        val grid = imageGrid ?: return
        grid.post { applyThumbnail(grid, key, drawable, bitmap, generation) }
    }

    /** 主线程把缓存结果贴到同 key 的可见格；没有目标时请求一次刷新让 getView 命中缓存。 */
    private fun applyThumbnail(
        grid: GridView,
        key: String,
        drawable: Drawable,
        bitmap: Bitmap?,
        generation: Int,
    ): Boolean {
        if (closed || generation != imageGeneration) return false
        var applied = false
        for (index in 0 until grid.childCount) {
            val image = grid.getChildAt(index) as? ImageView ?: continue
            if (image.tag == key) {
                if (bitmap != null) {
                    AnimatedImageLoader.clear(image)
                    image.setImageBitmap(bitmap)
                } else {
                    AnimatedImageLoader.bind(image, drawable)
                }
                applied = true
            }
        }
        if (!applied) requestThumbnailRefresh(grid, generation)
        // 首张图已经贴到网格上，此时再补分类封面，避免封面解码抢在首图前面。
        if (applied) openFolderCoverGate()
        return applied
    }

    /** 首图可见（或网格本来没有图片）后一次性提交挂起的分类封面请求。 */
    private fun openFolderCoverGate() {
        if (folderCoverGateOpen) return
        folderCoverGateOpen = true
        if (pendingFolderCovers.isEmpty()) return
        val pending = pendingFolderCovers.toList()
        pendingFolderCovers.clear()
        pending.forEach { it() }
    }

    private fun requestThumbnailRefresh(grid: GridView, generation: Int) {
        thumbnailRefreshGeneration = generation
        if (thumbnailRefreshPosted) return
        thumbnailRefreshPosted = true
        grid.postOnAnimation {
            thumbnailRefreshPosted = false
            if (!closed && thumbnailRefreshGeneration == imageGeneration) {
                grid.invalidateViews()
            }
        }
    }

    /**
     * 浏览模式首屏先解码网格首图，再补分类封面：封面解码与首图共用 SAF provider，
     * 若同时排队会把首图解码挤到后面。封面请求先挂起，等首图真正贴到网格（或网格为空）后
     * 按可见卡片一次性提交，不靠固定延时。
     */
    private fun loadFolderCover(folder: ImageEntry, size: Int, image: ImageView) {
        val key = "folder:${folder.key}:$size"
        image.tag = key
        val target = WeakReference(image)
        if (browseOnly && !folderCoverGateOpen) {
            pendingFolderCovers += { submitFolderCover(folder, size, key, target) }
            return
        }
        submitFolderCover(folder, size, key, target)
    }

    private fun submitFolderCover(
        folder: ImageEntry,
        size: Int,
        key: String,
        target: WeakReference<ImageView>,
    ) {
        // 已枚举分类（根/外部分组/首屏目标）直接取快照里按修改时间降序的最后一张，
        // 等价于 coverFile 的“最早修改”封面；只有未枚举分类才回退实时枚举。
        val snapped = currentScan?.imagesByFolder?.get(folder.key)?.lastOrNull()
        runCatching {
            folderCoverExecutor.execute {
                if (closed) return@execute
                // 卡片可能已被回收给别的分类：先确认目标视图仍绑定本次封面再解码。
                val currentView = target.get() ?: return@execute
                if (currentView.tag != key) return@execute
                val cover = snapped
                    ?: runCatching { ImageFolderStore.coverFile(folder) }.getOrNull()
                    ?: return@execute
                val coverKey = thumbnailKey(cover, size)
                val cachedBitmap = synchronized(thumbnailCache) { thumbnailCache.get(coverKey) }
                val drawable = if (cachedBitmap == null) {
                    runCatching { decodeEntry(cover, size) }
                        .onFailure { error ->
                            Log.warn("解码分类封面失败: ${cover.key}", error)
                            reportDecodeAccessError(error)
                        }
                        .getOrNull()
                } else {
                    null
                }
                val bitmap = cachedBitmap ?: (drawable as? BitmapDrawable)?.bitmap?.also {
                    synchronized(thumbnailCache) { thumbnailCache.put(coverKey, it) }
                }
                if (bitmap == null && drawable == null) return@execute
                target.get()?.post {
                    val view = target.get() ?: return@post
                    if (!closed && view.tag == key) {
                        view.clearColorFilter()
                        view.setPadding(0, 0, 0, 0)
                        view.scaleType = ImageView.ScaleType.CENTER_CROP
                        if (bitmap != null) {
                            AnimatedImageLoader.clear(view)
                            view.setImageBitmap(bitmap)
                        } else {
                            AnimatedImageLoader.bind(view, drawable!!)
                        }
                    }
                }
            }
        }
    }

    /**
     * 文档直接交给 ImageDecoder，不再为缩略图复制文件；真实文件仍走原路径。
     *
     * 仅在真正需要新解码（缓存未命中）时校验持久读授权：旧的 ImageEntry 不能借助
     * 进程内残留的临时授权，在模块授权被清除后继续读取新数据。
     */
    private fun decodeEntry(entry: ImageEntry, maxSize: Int): Drawable? {
        entry.localFile?.let { return AnimatedImageLoader.decode(it, maxSize) }
        entry.documentUri?.let { uri ->
            val host = context.applicationContext ?: context
            if (!ImageEntry.hasPersistedRead(host, entry.key)) {
                throw ImageAccessException("目录授权已失效，请重新选择授权目录")
            }
            return AnimatedImageLoader.decode(context, uri, maxSize, entry.name)
        }
        return null
    }

    /** 解码阶段发现授权被清除时提示重新授权（限制频率，避免缩略图逐张弹窗）。 */
    private fun reportDecodeAccessError(error: Throwable) {
        if (error !is ImageAccessException) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastAccessErrorAt < DECODE_ERROR_INTERVAL_MS) return
            lastAccessErrorAt = now
        }
        val message = error.message ?: "目录授权已失效，请重新选择授权目录"
        titleView.post {
            if (!closed) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun thumbnailKey(entry: ImageEntry, size: Int): String =
        "${entry.key}:${entry.modified}:${entry.size}:$size"

    /**
     * 只在真正发送的边界把选中的文档实质化成宿主私有文件；列表、缩略图、预览都不复制。
     */
    private fun performAction(entry: ImageEntry) {
        val action = selectedAction ?: return
        if (performingAction) return
        performingAction = true
        val generation = imageGeneration
        val submit = runCatching {
            ioExecutor.execute {
                val materialized = runCatching { ImageSendCache.fileFor(context, entry) }
                postToPanel {
                    if (generation != imageGeneration) {
                        performingAction = false
                        return@postToPanel
                    }
                    materialized.onFailure { error ->
                        performingAction = false
                        Log.error("${action.label}失败: ${entry.key}", error)
                        Toast.makeText(
                            context,
                            error.message ?: "${action.label}失败",
                            Toast.LENGTH_LONG,
                        ).show()
                    }.onSuccess { file -> performActionWithFile(action, entry, file, generation) }
                }
            }
        }
        submit.onFailure {
            performingAction = false
            Log.error("无法调度${action.label}", it)
            Toast.makeText(context, it.message ?: "${action.label}失败", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 异步操作的完成语义：
     * - 宿主确认原生入队即关闭面板，不等上传终态；
     * - 真实终态只处理一次（可能早于入队回调同步到达），成功才记历史，失败仍提示；
     * - 入队后面板已关闭，终态照常处理历史/提示，但不重新走已关闭的 IO 或会话解析；
     * - 用户手动关闭且未入队时，晚到的终态直接丢弃；
     * - 旧操作的终态不会关闭之后新打开的面板。
     */
    private fun performActionWithFile(
        action: ImagePanelAction,
        entry: ImageEntry,
        file: File,
        generation: Int,
    ) {
        val panel = panelDialog
        val enqueued = AtomicBoolean()
        val terminal = AtomicBoolean()
        val finish: (Result<Unit>) -> Unit = { result ->
            if (terminal.compareAndSet(false, true)) {
                mainHandler.post {
                    runCatching {
                        if (enqueued.get() || (!closed && generation == imageGeneration)) {
                            performImagePanelAction(
                                send = { result },
                                recordUsage = { ImageFolderStore.recordUsage(entry) },
                                onUsageFailure = { Log.error("发送已提交，更新表情历史失败", it) },
                            ).onSuccess {
                                if (panelDialog === panel) panel?.dismiss()
                            }.onFailure {
                                performingAction = false
                                Log.error("${action.label}失败: ${entry.key}", it)
                                Toast.makeText(
                                    context,
                                    it.message ?: "${action.label}失败",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }.onFailure { Log.error("${action.label}终态处理失败: ${entry.key}", it) }
                }
            }
        }
        val onEnqueued: () -> Unit = {
            runCatching {
                enqueued.set(true)
                mainHandler.post {
                    if (panelDialog === panel) panel?.dismiss()
                }
            }
        }
        val async = action.performAsync
        val active = { !closed && generation == imageGeneration }
        if (async != null) {
            runCatching { async(file, finish, active, onEnqueued) }
                .onFailure { finish(Result.failure(it)) }
        } else {
            finish(runCatching { action.perform(file).getOrThrow() })
        }
    }

    private fun showEmoticonPreview(entry: ImageEntry) {
        val preview = ImageView(context).apply {
            tag = "preview:${entry.key}"
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            background = rounded(colors.preview, context.dp(20).toFloat())
            clipToOutline = true
            outlineProvider = roundedOutline(context.dp(20).toFloat())
            minimumHeight = context.dp(200)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(20)
            setPadding(pad, context.dp(8), pad, context.dp(24))
            addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(280),
                ).apply { bottomMargin = context.dp(20) },
            )
            if (entry.canDelete) {
                addView(
                    filledButton(
                        "删除",
                        ContextCompat.getColor(context, R.color.qqz_error),
                        ContextCompat.getColor(context, R.color.qqz_on_error),
                    ) { confirmDeleteEmoticon(entry) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        context.dp(48),
                    ),
                )
            } else {
                addView(
                    TextView(context).apply {
                        text = "该目录为只读授权，不能删除"
                        setTextColor(colors.muted)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        gravity = Gravity.CENTER
                        setPadding(0, context.dp(12), 0, context.dp(12))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
        previewDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("表情预览")
            .setView(content)
            .create()
        previewDialog = dialog
        dialog.setOnDismissListener {
            AnimatedImageLoader.clear(preview)
            if (previewDialog === dialog) previewDialog = null
        }
        dialog.show()
        submitIo("预览解码") {
            val drawable = runCatching { decodeEntry(entry, maxSize = 720) }
                .onFailure { error ->
                    Log.warn("预览解码失败: ${entry.key}", error)
                    reportDecodeAccessError(error)
                }
                .getOrNull()
            preview.post {
                if (closed || previewDialog !== dialog) return@post
                if (drawable != null) {
                    AnimatedImageLoader.bind(preview, drawable)
                } else {
                    preview.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }
        }
    }

    private fun confirmDeleteEmoticon(entry: ImageEntry) {
        if (!entry.canDelete) {
            Toast.makeText(context, "该目录为只读授权，不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        val message = TextView(context).apply {
            text = "确定删除这个表情？删除后无法恢复。"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(12))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("删除表情")
            .setView(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
                    .coerceAtLeast(context.dp(280)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val delete = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            delete.setTextColor(ContextCompat.getColor(context, R.color.qqz_error))
            delete.setOnClickListener {
                delete.isEnabled = false
                submitIo(
                    "删除表情",
                    onSuccess = {
                        dialog.dismiss()
                        previewDialog?.dismiss()
                        bindFolders()
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        delete.isEnabled = true
                        Log.error("删除表情失败: ${entry.key}", error)
                        Toast.makeText(context, error.message ?: "删除失败", Toast.LENGTH_SHORT)
                            .show()
                    },
                ) { ImageFolderStore.deleteImage(entry) }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteFolder(folder: ImageEntry) {
        if (folder.key !in deletableFolders) {
            Toast.makeText(context, "这个文件夹不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        val name = folderLabel(folder)
        val message = TextView(context).apply {
            text = "确定删除文件夹「$name」？其中的图片会一起删除，且无法恢复。"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(12))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("删除文件夹")
            .setView(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
                    .coerceAtLeast(context.dp(280)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val delete = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            delete.setTextColor(ContextCompat.getColor(context, R.color.qqz_error))
            delete.setOnClickListener {
                delete.isEnabled = false
                submitIo(
                    "删除文件夹",
                    onSuccess = {
                        dialog.dismiss()
                        if (selected[0]?.key == folder.key) selected[0] = null
                        bindFolders()
                        Toast.makeText(context, "已删除文件夹", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        delete.isEnabled = true
                        Log.error("删除文件夹失败: ${folder.key}", error)
                        Toast.makeText(context, error.message ?: "删除失败", Toast.LENGTH_SHORT)
                            .show()
                    },
                ) { ImageFolderStore.deleteFolder(folder) }
            }
        }
        dialog.show()
    }

    private fun refreshDeleteFolderButton() {
        val folder = selected[0]
        deleteFolderButton?.visibility =
            if (folder != null && folder.key in deletableFolders) View.VISIBLE else View.GONE
    }

    /** 统一刷新只读/可写相关的按钮状态：授权失效或只读时禁用全部变更入口。 */
    private fun refreshFolderActions() {
        refreshDeleteFolderButton()
        createFolderButton?.let { button ->
            button.isEnabled = rootWritable
            button.alpha = if (rootWritable) 1f else 0.4f
        }
        saveButton?.let { button ->
            val folder = selected[0]
            val writable = folder != null && folder.canWrite
            button.isEnabled = writable
            button.alpha = if (writable) 1f else 0.4f
        }
    }

    private fun deleteFolderButton(): ImageButton {
        val selectableBackground = TypedValue().also {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true,
            )
        }.resourceId
        return ImageButton(context).apply {
            deleteFolderButton = this
            setImageResource(R.drawable.ic_delete_folder)
            setColorFilter(ContextCompat.getColor(context, R.color.qqz_error))
            setBackgroundResource(selectableBackground)
            contentDescription = "删除文件夹"
            visibility = View.GONE
            val padding = context.dp(12)
            setPadding(padding, padding, padding, padding)
            disableEmptyLongClick()
            setOnClickListener {
                selected[0]?.let(::confirmDeleteFolder)
            }
        }
    }

    private fun createActionMenuButton(): ImageButton {
        val selectableBackground = TypedValue().also {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true,
            )
        }.resourceId
        return ImageButton(context).apply {
            actionButton = this
            bindActionIcon(this)
            setColorFilter(colors.primary)
            setBackgroundResource(selectableBackground)
            val padding = context.dp(12)
            setPadding(padding, padding, padding, padding)
            disableEmptyLongClick()
            setOnClickListener { showActionMenu(it) }
        }
    }

    private fun bindActionIcon(button: ImageButton) {
        val action = selectedAction ?: return
        button.setImageResource(action.iconRes)
        button.contentDescription = action.contentDescription
    }

    private fun showActionMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            actions.forEachIndexed { index, action ->
                menu.add(0, index, index, action.label).isChecked = action === selectedAction
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                val action = actions.getOrNull(item.itemId)
                    ?: return@setOnMenuItemClickListener false
                selectedAction = action
                onActionSelected?.invoke(action.id)
                actionButton?.let(::bindActionIcon)
                true
            }
            show()
        }
    }

    private fun loadPreview() {
        val preview = previewView ?: return
        val progress = previewProgress ?: return
        if (imageUrls.isEmpty()) {
            progress.visibility = View.GONE
            return
        }
        Thread({
            val result = runCatching { ImageDownloader.fetch(imageUrls) }
            preview.post {
                progress.visibility = View.GONE
                val downloaded = result.getOrNull()
                val drawable = downloaded?.let { AnimatedImageLoader.decode(it.bytes, maxSize = 720) }
                if (drawable != null) {
                    pending[0] = downloaded
                    AnimatedImageLoader.bind(preview, drawable)
                } else {
                    preview.setImageResource(android.R.drawable.ic_menu_report_image)
                }
                result.exceptionOrNull()?.let {
                    Log.error("加载聊天图片预览失败（${imageUrls.size} 个候选地址）", it)
                }
            }
        }, "QQZygisk-ImagePreview").start()
    }

    private fun showCreateFolder() {
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val inputLayout = TextInputLayout(
            context,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle,
        ).apply {
            hint = "文件夹名称"
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val box = FrameLayout(context).apply {
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
            addView(
                inputLayout,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("新建文件夹")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create()
        input.doAfterTextChanged {
            inputLayout.error = null
        }
        dialog.setOnShowListener {
            val create = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            create.setOnClickListener {
                create.isEnabled = false
                submitIo(
                    "新建文件夹",
                    onSuccess = { created ->
                        selected[0] = created
                        bindFolders()
                        dialog.dismiss()
                    },
                    onFailure = { error ->
                        create.isEnabled = true
                        Log.error("创建保存文件夹失败", error)
                        inputLayout.error = error.message ?: "无法创建文件夹"
                    },
                ) { ImageFolderStore.createFolder(input.text.toString()) }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun saveCurrent() {
        val image = pending[0]
        val folder = selected[0]
        if (image == null) {
            Toast.makeText(context, "图片还在加载，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (folder == null) {
            Toast.makeText(context, "请先选择或新建文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        if (!folder.canWrite) {
            Toast.makeText(context, "该目录为只读授权，无法保存", Toast.LENGTH_SHORT).show()
            return
        }
        val folderName = folderLabel(folder)
        saveButton?.isEnabled = false
        submitIo(
            "保存聊天图片",
            onSuccess = {
                Toast.makeText(context, "已保存到「$folderName」", Toast.LENGTH_SHORT).show()
                panelDialog?.dismiss()
            },
            onFailure = { error ->
                saveButton?.isEnabled = true
                Log.error("保存聊天图片失败", error)
                Toast.makeText(context, error.message ?: "保存失败", Toast.LENGTH_SHORT).show()
            },
        ) { ImageFolderStore.saveImage(folder, image.bytes, image.extension) }
    }

    private fun filledButton(
        label: String,
        background: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            this.background = rounded(background, context.dp(16).toFloat())
            setOnClickListener { onClick() }
        }
    }

    private fun createFolderButton(): ImageButton {
        val selectableBackground = TypedValue().also {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true,
            )
        }.resourceId
        return ImageButton(context).apply {
            createFolderButton = this
            setImageResource(R.drawable.ic_create_folder)
            setColorFilter(colors.primary)
            setBackgroundResource(selectableBackground)
            contentDescription = "新建文件夹"
            isEnabled = rootWritable
            alpha = if (rootWritable) 1f else 0.4f
            val padding = context.dp(12)
            setPadding(padding, padding, padding, padding)
            disableEmptyLongClick()
            setOnClickListener {
                runCatching { showCreateFolder() }.onFailure { error ->
                    Log.error("打开新建文件夹弹窗失败", error)
                    Toast.makeText(context, "无法打开新建文件夹弹窗", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun View.disableEmptyLongClick() {
        isHapticFeedbackEnabled = false
        isLongClickable = false
        setOnLongClickListener(null)
    }

    private fun rounded(
        color: Int,
        radius: Float = 0f,
        stroke: Int? = null,
        strokeWidth: Int = 0,
        topRadius: Float = radius,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(
            topRadius, topRadius, topRadius, topRadius,
            radius, radius, radius, radius,
        )
        if (stroke != null && strokeWidth > 0) {
            setStroke(strokeWidth, stroke)
        }
    }

    private fun Int.toDrawable() = GradientDrawable().apply { setColor(this@toDrawable) }

    private fun roundedOutline(radius: Float) = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private data class PanelColors(
        val surface: Int,
        val preview: Int,
        val selected: Int,
        val primary: Int,
        val onPrimary: Int,
        val secondary: Int,
        val onSecondary: Int,
        val onSurface: Int,
        val muted: Int,
        val handle: Int,
    ) {
        companion object {
            fun from(context: Context): PanelColors {
                fun color(resourceId: Int) = ContextCompat.getColor(context, resourceId)

                val primary = color(R.color.qqz_primary)
                val onSurfaceVariant = color(R.color.qqz_on_surface_variant)
                return PanelColors(
                    surface = color(R.color.qqz_surface),
                    preview = color(R.color.qqz_surface_container_high),
                    selected = ColorUtils.setAlphaComponent(primary, 0x1f),
                    primary = primary,
                    onPrimary = color(R.color.qqz_on_primary),
                    secondary = color(R.color.qqz_secondary_container),
                    onSecondary = color(R.color.qqz_on_secondary_container),
                    onSurface = color(R.color.qqz_on_surface),
                    muted = onSurfaceVariant,
                    handle = ColorUtils.setAlphaComponent(onSurfaceVariant, 0x55),
                )
            }
        }
    }

    companion object {
        private const val IMAGE_COLUMNS = 5
        private const val DECODE_ERROR_INTERVAL_MS = 5000L
        private const val PANEL_HORIZONTAL_PADDING = 12
        private const val FOLDER_COLUMN_WIDTH = 56
        private const val FOLDER_GRID_GAP = 6
        private const val IMAGE_GAP = 4
        /** 面板生命周期内保留的真实图片列表数量：最近访问的分类，足够覆盖来回切换。 */
        private const val IMAGE_LIST_CACHE_SIZE = 8
        private const val THUMBNAIL_CACHE_KB = 16 * 1024
        /** 首图预热请求的独立键前缀，与按代区分的可见格请求分开去重。 */
        private const val PREFETCH_REQUEST_PREFIX = "prefetch:"

        fun show(
            host: Context,
            imageUrls: List<String> = emptyList(),
            actions: List<ImagePanelAction> = emptyList(),
            initialActionId: String? = null,
            onActionSelected: ((String) -> Unit)? = null,
        ) {
            require(actions.map(ImagePanelAction::id).distinct().size == actions.size) {
                "图片操作 ID 不能重复"
            }
            host.injectModuleAppResources()
            val moduleLoader = SaveImagePanel::class.java.classLoader ?: host.classLoader
            val themed = ModuleContext(host, R.style.Theme_QQZygisk_MaterialDialog, moduleLoader)
            SaveImagePanel(
                themed,
                imageUrls,
                actions,
                initialActionId,
                onActionSelected,
            ).show()
        }
    }
}

private data class FolderSnapshot(
    val scan: FolderScan,
    val labels: Map<String, String>,
    val deletable: Set<String>,
    val lastKey: String?,
    val rootWritable: Boolean,
)

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/** Module views must use a fresh inflater: cloneInContext also copies the host's Factory2. */
internal class ModuleContext(
    base: Context,
    themeRes: Int,
    private val moduleLoader: ClassLoader,
) : ContextThemeWrapper(base, themeRes) {
    private var inflater: LayoutInflater? = null

    override fun getClassLoader(): ClassLoader = moduleLoader

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            inflater?.let { return it }
            return ModuleLayoutInflater(this).also { inflater = it }
        }
        return super.getSystemService(name)
    }
}

private class ModuleLayoutInflater(context: Context) : LayoutInflater(context) {
    init {
        factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
                if ('.' !in name || name.startsWith("android.")) return null
                // Framework constructor caches can also contain the host's AndroidX classes.
                // Resolve qualified module views directly with the module Context's loader.
                val type = context.classLoader.loadClass(name).asSubclass(View::class.java)
                return type.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(context, attrs)
            }

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                onCreateView(null, name, context, attrs)
        }
    }

    override fun cloneInContext(newContext: Context): LayoutInflater = ModuleLayoutInflater(newContext)

    override fun onCreateView(name: String, attrs: AttributeSet): View {
        for (prefix in listOf("android.widget.", "android.webkit.", "android.app.")) {
            try {
                return createView(name, prefix, attrs)
            } catch (_: ClassNotFoundException) {
                // Continue with the framework prefixes, matching PhoneLayoutInflater.
            }
        }
        return super.onCreateView(name, attrs)
    }
}
