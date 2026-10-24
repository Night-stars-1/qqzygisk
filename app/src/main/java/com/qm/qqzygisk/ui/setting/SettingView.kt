package com.qm.qqzygisk.ui.setting

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.qm.qqzygisk.hook.app.QQEntry.settings
import com.qm.qqzygisk.hook.app.host.HostType
import com.qm.qqzygisk.hook.app.chat.ImageDirectoryGrants
import com.qm.qqzygisk.hook.app.chat.ImageFolderStore
import com.qm.qqzygisk.hook.app.hooker.EmoticonButtonHooker
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.ui.component.setting.SettingSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingView() {
    val context = LocalContext.current
    remember(context) { HookSettings.initialize(context) }
    var showPathSettings by rememberSaveable { mutableStateOf(false) }
    var closePathSettingsAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun closePathSettings() {
        closePathSettingsAction?.invoke()
        showPathSettings = false
    }
    BackHandler(enabled = showPathSettings) { closePathSettings() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(if (showPathSettings) "表情目录" else "设置")
                },
                navigationIcon = {
                    if (showPathSettings) {
                        IconButton(onClick = { closePathSettings() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        if (showPathSettings) {
            PathSettingsPage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onRegisterExit = { closePathSettingsAction = it },
            )
        } else {
            MainSettings(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onOpenPathSettings = { showPathSettings = true },
            )
        }
    }
}

@Composable
private fun MainSettings(
    modifier: Modifier = Modifier,
    onOpenPathSettings: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        val availableSettings = if (ImageFolderStore.hostType != HostType.QQ) {
            listOf(EmoticonButtonHooker.toSettingData())
        } else {
            settings
        }
        availableSettings.forEach { setting ->
            var isEnabled by remember(setting.key) {
                mutableStateOf(
                    HookSettings.isEnabled(setting.key, setting.defaultEnabled)
                )
            }
            SettingSwitch(
                title = setting.name,
                description = setting.description,
                checked = isEnabled,
                onCheckedChange = { enabled ->
                    HookSettings.setEnabled(setting.key, enabled)
                    isEnabled = enabled
                }
            )
        }
        var historyLimitText by remember {
            mutableStateOf(ImageFolderStore.historyLimit().toString())
        }
        OutlinedTextField(
            value = historyLimitText,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() }.take(3)
                historyLimitText = filtered
                filtered.toIntOrNull()?.let { value ->
                    if (value in ImageFolderStore.MIN_HISTORY_LIMIT..ImageFolderStore.MAX_HISTORY_LIMIT) {
                        HookSettings.setInt(ImageFolderStore.HISTORY_LIMIT_KEY, value)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("历史表情数量") },
            supportingText = { Text("按发送次数显示，范围 1–500，默认 80") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPathSettings)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "表情目录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "QQ Zygisk、FunBox、TGStickersExported",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        ModuleUpdateSetting()
    }
}

@Composable
internal fun PathSettingsPage(
    modifier: Modifier = Modifier,
    onRegisterExit: (() -> Unit) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        PathSettings(onRegisterExit)
    }
}

@Composable
private fun ColumnScope.PathSettings(onRegisterExit: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val imageKey = ImageFolderStore.pathKey
    val imageDefault = ImageFolderStore.defaultPath
    val funBoxDefault = ImageFolderStore.defaultFunBoxPath
    val tgStickersDefault = ImageFolderStore.defaultTgStickersPath
    val initialPaths = remember { ImageFolderStore.sourcePaths }
    // 草稿与已保存值都用 rememberSaveable：旋转/进程恢复后待保存的路径和已选目录仍然存在。
    var imagePath by rememberSaveable { mutableStateOf(initialPaths.images) }
    var funBoxPath by rememberSaveable { mutableStateOf(initialPaths.funBox) }
    var tgStickersPath by rememberSaveable { mutableStateOf(initialPaths.tgStickers) }
    var savedImagePath by rememberSaveable { mutableStateOf(initialPaths.images) }
    var savedFunBoxPath by rememberSaveable { mutableStateOf(initialPaths.funBox) }
    var savedTgStickersPath by rememberSaveable { mutableStateOf(initialPaths.tgStickers) }
    var imageError by rememberSaveable { mutableStateOf<String?>(null) }
    var funBoxError by rememberSaveable { mutableStateOf<String?>(null) }
    var tgStickersError by rememberSaveable { mutableStateOf<String?>(null) }
    var statusVersion by remember { mutableIntStateOf(0) }
    var confirmMigrate by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var migrateMessage by remember { mutableStateOf<String?>(null) }
    val migrationAlive = remember { AtomicBoolean(true) }

    val hostPackage = remember { ImageDirectoryGrants.hostPackageName() }
    val hostAvailable = hostPackage != null
    val hostLabel = when (hostPackage) {
        "com.tencent.mm" -> "微信"
        "com.ss.android.ugc.aweme" -> "抖音"
        else -> "QQ Zygisk"
    }

    val savedSources: () -> List<String> = {
        listOf(savedImagePath, savedFunBoxPath, savedTgStickersPath)
    }
    // 放弃草稿时只释放模块自己取得、且没有被已保存来源采用的授权。
    val abandonDrafts: () -> Unit = {
        ImageDirectoryGrants.releaseProvisional(context, savedSources())
    }
    val activity = context.findActivity()
    DisposableEffect(Unit) {
        onRegisterExit(abandonDrafts)
        onDispose {
            migrationAlive.set(false)
            // 旋转、进程恢复不是真正放弃页面；只有 Activity 真正结束时才释放临时授权。
            if (activity?.isFinishing == true) abandonDrafts()
        }
    }
    DisposableEffect(activity) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statusVersion++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    fun normalize(value: String, default: String) = value.trim().ifEmpty { default }
    fun draftSources(): LinkedHashMap<String, String> = linkedMapOf(
        imageKey to normalize(imagePath, imageDefault),
        ImageFolderStore.FUNBOX_PATH_KEY to normalize(funBoxPath, funBoxDefault),
        ImageFolderStore.TG_STICKERS_PATH_KEY to normalize(tgStickersPath, tgStickersDefault),
    )
    // 唯一的配置提交点：保存前验证 content 目录仍有持久授权，失败时不写入任何设置。
    fun saveDrafts(): Boolean {
        val values = draftSources()
        val errors = ImageDirectoryGrants.validateSources(context, values)
        imageError = errors[imageKey]
        funBoxError = errors[ImageFolderStore.FUNBOX_PATH_KEY]
        tgStickersError = errors[ImageFolderStore.TG_STICKERS_PATH_KEY]
        if (errors.isNotEmpty()) return false
        values.forEach { (key, value) -> HookSettings.setString(key, value) }
        imagePath = values.getValue(imageKey)
        funBoxPath = values.getValue(ImageFolderStore.FUNBOX_PATH_KEY)
        tgStickersPath = values.getValue(ImageFolderStore.TG_STICKERS_PATH_KEY)
        savedImagePath = imagePath
        savedFunBoxPath = funBoxPath
        savedTgStickersPath = tgStickersPath
        // 被替换或清除的旧目录，只有模块取得过、且不再被任何来源引用时才撤销授权。
        ImageDirectoryGrants.releaseProvisional(context, values.values.toList())
        statusVersion++
        return true
    }
    fun applyPicked(
        outcome: ImageDirectoryGrants.GrantOutcome,
        onAccepted: (String) -> Unit,
        onError: (String?) -> Unit,
    ) {
        if (!outcome.granted) {
            onError(outcome.error ?: "目录授权失败，请重新选择")
            return
        }
        onError(null)
        onAccepted(outcome.uriString)
        statusVersion++
    }

    val imageStatus = remember(imagePath, statusVersion) {
        ImageDirectoryGrants.status(context, imagePath)
    }
    val funBoxStatus = remember(funBoxPath, statusVersion) {
        ImageDirectoryGrants.status(context, funBoxPath)
    }
    val tgStickersStatus = remember(tgStickersPath, statusVersion) {
        ImageDirectoryGrants.status(context, tgStickersPath)
    }
    val hasChanges = imagePath != savedImagePath ||
        funBoxPath != savedFunBoxPath ||
        tgStickersPath != savedTgStickersPath

    Spacer(modifier = Modifier.height(20.dp))
    DirectorySourceField(
        label = "$hostLabel 图片目录",
        supportingHint = "当前应用的新建文件夹和保存图片使用此目录",
        value = imagePath,
        defaultPath = imageDefault,
        status = imageStatus,
        error = imageError,
        hostAvailable = hostAvailable,
        onValueChange = {
            imagePath = it
            imageError = null
        },
        onPicked = { outcome -> applyPicked(outcome, { imagePath = it }, { imageError = it }) },
    )
    Spacer(modifier = Modifier.height(12.dp))
    DirectorySourceField(
        label = "FunBox 表情目录",
        value = funBoxPath,
        defaultPath = funBoxDefault,
        status = funBoxStatus,
        error = funBoxError,
        hostAvailable = hostAvailable,
        onValueChange = {
            funBoxPath = it
            funBoxError = null
        },
        onPicked = { outcome -> applyPicked(outcome, { funBoxPath = it }, { funBoxError = it }) },
    )
    Spacer(modifier = Modifier.height(12.dp))
    DirectorySourceField(
        label = "TGStickersExported 表情目录",
        value = tgStickersPath,
        defaultPath = tgStickersDefault,
        status = tgStickersStatus,
        error = tgStickersError,
        hostAvailable = hostAvailable,
        onValueChange = {
            tgStickersPath = it
            tgStickersError = null
        },
        onPicked = { outcome ->
            applyPicked(outcome, { tgStickersPath = it }, { tgStickersError = it })
        },
    )
    if (!hostAvailable) {
        Text(
            text = "目录授权只能由宿主申请：请从 QQ、微信或抖音的应用内设置打开本页面后再选择目录；单独安装的模块应用无法为宿主取得授权。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = { saveDrafts() },
        modifier = Modifier.align(Alignment.End),
        enabled = hasChanges,
    ) {
        Text("保存路径")
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = { confirmMigrate = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = !migrating,
    ) {
        Text(if (migrating) "正在迁移…" else "迁移 FunBox 表情到 QQ Zygisk")
    }
    Text(
        text = "按表情包名称复制到 QQ Zygisk 目录，不删除 FunBox 原文件。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(modifier = Modifier.height(24.dp))

    if (confirmMigrate) {
        AlertDialog(
            onDismissRequest = { confirmMigrate = false },
            title = { Text("迁移 FunBox 表情") },
            text = {
                Text("把当前 FunBox 目录里的表情复制到 QQ Zygisk 目录？不会删除 FunBox 原文件。未保存的路径会先保存。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmMigrate = false
                        // 迁移前仍把未保存草稿作为唯一提交点保存；授权失效则不迁移也不写设置。
                        if (!saveDrafts()) return@TextButton
                        val qq = imagePath
                        val funBox = funBoxPath
                        migrating = true
                        Thread({
                            val message = runCatching {
                                ImageFolderStore.migrateFunBoxToQqZygisk(funBox, qq)
                            }.fold(
                                onSuccess = { result ->
                                    if (result.packs == 0) {
                                        "没有可迁移的 FunBox 表情"
                                    } else {
                                        "已处理 ${result.packs} 个表情包，复制 ${result.copied} 张，跳过 ${result.skipped} 张"
                                    }
                                },
                                onFailure = { it.message ?: "迁移失败" },
                            )
                            Handler(Looper.getMainLooper()).post {
                                if (!migrationAlive.get()) return@post
                                migrating = false
                                migrateMessage = message
                            }
                        }, "QQZygisk-FunBoxMigrate").start()
                    },
                ) {
                    Text("迁移")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMigrate = false }) {
                    Text("取消")
                }
            },
        )
    }
    migrateMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { migrateMessage = null },
            title = { Text("迁移结果") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { migrateMessage = null }) {
                    Text("知道了")
                }
            },
        )
    }
}

/**
 * 单个来源目录：保留可手动编辑的绝对路径，同时提供目录授权选择和状态。
 *
 * 每个字段各自注册选择器，待选择结果的来源槽位由字段本身确定；旋转后
 * ActivityResultRegistry 会恢复同一个 launcher 和待处理结果。
 */
@Composable
private fun DirectorySourceField(
    label: String,
    value: String,
    defaultPath: String,
    status: ImageDirectoryGrants.SourceStatus,
    error: String?,
    hostAvailable: Boolean,
    onValueChange: (String) -> Unit,
    onPicked: (ImageDirectoryGrants.GrantOutcome) -> Unit,
    supportingHint: String? = null,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ImageDirectoryGrants.OpenDirectoryTreeContract(),
    ) { picked ->
        // 取消选择不产生结果，也不覆盖草稿或已保存配置。
        if (picked != null) {
            onPicked(ImageDirectoryGrants.takePersistedGrant(context, picked))
        }
    }
    val statusColor = when (status.state) {
        ImageDirectoryGrants.GrantState.GRANTED -> MaterialTheme.colorScheme.primary
        ImageDirectoryGrants.GrantState.READ_ONLY,
        ImageDirectoryGrants.GrantState.PATH -> MaterialTheme.colorScheme.onSurfaceVariant
        ImageDirectoryGrants.GrantState.NEEDS_REAUTHORIZATION,
        ImageDirectoryGrants.GrantState.INVALID -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            minLines = 2,
            maxLines = 3,
            isError = error != null,
        )
        if (supportingHint != null) {
            Text(
                text = supportingHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    // 初始位置只是导航提示；失效授权也可定位原目录，权限仍须由用户重新确认。
                    val initial = ImageDirectoryGrants.initialTreeUriFor(value)
                    launcher.launch(initial)
                },
                modifier = Modifier.semantics {
                    contentDescription = "$label 选择目录"
                },
                enabled = hostAvailable,
            ) {
                Text("选择目录")
            }
            if (status.usesDocument) {
                TextButton(onClick = { onValueChange(defaultPath) }) {
                    Text("改用默认路径")
                }
            }
        }
        if (!hostAvailable) {
            Text(
                text = "请在宿主应用内选择目录授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
