package cn.liuzf.lighthouse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink: Color @Composable get() = LocalLighthousePalette.current.background
private val Surface: Color @Composable get() = LocalLighthousePalette.current.surface
private val Mint: Color @Composable get() = LocalLighthousePalette.current.primary
private val Muted: Color @Composable get() = LocalLighthousePalette.current.muted
private val Coral: Color @Composable get() = LocalLighthousePalette.current.accent

class MainActivity : ComponentActivity() {
    private var pendingLive = false
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) {
            Notices.reconcile(this, repo().state.value.snapshot)
            if (pendingLive) startLive()
        } else Toast.makeText(this, "请允许通知，才能在通知栏看到作业提醒", Toast.LENGTH_LONG).show()
        pendingLive = false
    }
    private fun startLive() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, LiveSyncService::class.java)) }
            .onFailure { Toast.makeText(this, "系统暂不允许开启实时模式，已保留普通后台同步，请稍后重试", Toast.LENGTH_LONG).show() }
    }
    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else RunSupport.open(this, "notifications")
    }
    private fun enableLive() {
        if (!repo().settings.configured()) return
        if (Build.VERSION.SDK_INT >= 33 && !Notices.canNotify(this)) { pendingLive = true; requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else startLive()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { repo().refresh(); delay(60_000) }
            }
        }
        setContent {
            var themeId by remember { mutableStateOf(repo().settings.prefs.getString("theme", "mint") ?: "mint") }
            val palette = LighthouseThemes.find { it.id == themeId } ?: LighthouseThemes.first()
            SideEffect {
                enableEdgeToEdge(statusBarStyle = if (palette.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = if (palette.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
            }
            CompositionLocalProvider(LocalLighthousePalette provides palette) {
                val colors = if (palette.dark) darkColorScheme(primary = Mint, background = Ink, surface = Surface, onSurface = palette.text, onBackground = palette.text, secondary = Coral)
                    else lightColorScheme(primary = Mint, background = Ink, surface = Surface, onSurface = palette.text, onBackground = palette.text, secondary = Coral)
                MaterialTheme(colorScheme = colors) {
                    LighthouseScreen(repo(), { enableLive() }, { stopService(Intent(this, LiveSyncService::class.java)) }, { allowNotifications() }, themeId, {
                        themeId = it; repo().settings.prefs.edit().putString("theme", it).apply()
                    })
                }
            }
        }
    }
}

@Composable
private fun LighthouseScreen(repository: Repository, startLive: () -> Unit, stopLive: () -> Unit, requestNotification: () -> Unit, themeId: String, chooseTheme: (String) -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val settings = repository.settings
    val taskRevision by repository.tasks.revision.collectAsStateWithLifecycle()
    val allTasks = remember(snapshot, taskRevision) { repository.tasks.all(snapshot) }
    val scope = rememberCoroutineScope()
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("work") }
    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("active") }
    val workScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val reminderScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val mineScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var showAddTask by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showHours by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showXiaomi by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(snapshot.now()) }
    var hours by remember { mutableIntStateOf(settings.urgentHours) }
    var live by remember { mutableStateOf(settings.prefs.getBoolean("live", false)) }
    var notificationsReady by remember { mutableStateOf(RunSupport.channelEnabled(repository.context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.activity.compose.BackHandler(enabled = page != "work") { page = "work" }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = repository.state.value.snapshot
                now = current.now(); live = settings.prefs.getBoolean("live", false)
                notificationsReady = RunSupport.channelEnabled(repository.context)
                Notices.reconcile(repository.context, current)
                delay(2000)
            }
        }
    }
    val active = allTasks.filter { !repository.tasks.ignored(it.id) && it.active(now) }.sortedWith(compareBy<Assignment> { !repository.tasks.focused(it.id) }.thenBy { it.deadline ?: Long.MAX_VALUE })
    val urgent = repository.tasks.reminders(snapshot, hours).size
    val visible = allTasks.filter {
        if (selected == "ignored") repository.tasks.ignored(it.id) else !repository.tasks.ignored(it.id) && when (selected) {
            "active" -> it.active(now)
            "focused" -> repository.tasks.focused(it.id) && it.active(now)
            "expired" -> it.category == "expired" || (it.category == "active" && (it.deadline ?: Long.MAX_VALUE) <= now)
            "completed" -> it.category == "completed"
            else -> it.category == "upcoming" || it.category == "unknown"
        }
    }.sortedWith(compareBy<Assignment> { !repository.tasks.focused(it.id) }.thenBy { it.deadline ?: Long.MAX_VALUE })
    Scaffold(containerColor = Ink, bottomBar = {
        NavigationBar(containerColor = Ink) {
            listOf(Triple("work", "作业", Icons.Outlined.Assignment), Triple("reminders", "提醒", Icons.Outlined.NotificationsActive), Triple("mine", "我的", Icons.Outlined.PersonOutline)).forEach { (key, label, icon) ->
                NavigationBarItem(selected = page == key, onClick = { page = key }, icon = { Icon(icon, null) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Mint.copy(alpha = .14f), selectedIconColor = Mint, selectedTextColor = Mint, unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }) { padding ->
        LazyColumn(state = when (page) { "work" -> workScroll; "reminders" -> reminderScroll; else -> mineScroll }, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "$page-header") {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when (page) { "work" -> "作业灯塔"; "reminders" -> "提醒"; else -> "我的" }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(when (page) { "work" -> "要做的事，一目了然"; "reminders" -> "安排提醒，关注临近截止"; else -> "账号连接与应用设置" }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    if (page == "work") IconButton(onClick = { showAddTask = true }) { Icon(Icons.Outlined.Add, "添加手动任务", tint = Mint) }
                    if (page == "work") IconButton(onClick = { scope.launch { repository.refresh(true) } }, enabled = settings.configured() && !state.refreshing) {
                        if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Sync, "刷新作业", tint = Mint)
                    }
                }
            }
            when (page) {
                "work" -> {
                    item(key = "work-summary") {
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(LocalLighthousePalette.current.hero), RoundedCornerShape(25.dp)).padding(24.dp)) {
                            Text("把要紧的事，留在眼前。", color = Mint, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                                Text("${active.size}", fontSize = 54.sp, fontWeight = FontWeight.Bold)
                                Text(" 项待完成", color = LocalLighthousePalette.current.text, modifier = Modifier.padding(bottom = 10.dp))
                            }
                            Text(when { !settings.configured() && active.isEmpty() -> "可先添加手动任务，或在我的连接学习通"; snapshot.lastSuccess == 0L && active.isEmpty() -> "尚未取得作业结果，请先检查连接"; active.isEmpty() -> "当前没有可提交的待办"; urgent > 0 -> "$urgent 项任务需要关注，前往提醒页查看规则"; else -> active.filter { it.deadline != null }.minByOrNull { it.deadline!! }?.let { "最近一项${remaining(it.deadline!! - now).removePrefix("剩余 ")}截止" } ?: "手动任务可随时提上日程，优先关注" }, color = Muted, fontSize = 12.sp)
                            Text(if (snapshot.lastSuccess > 0) "上次云端同步 · " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(snapshot.lastSuccess * 1000)) else "尚未同步", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                    if (!settings.configured()) item { PageRow("开始使用", "在“我的”配置云端连接和通知权限", { page = "mine" }) }
                    if (state.networkError != null || snapshot.error != null) item {
                        PageRow("同步需要检查", state.networkError ?: "云端同步异常，保留上次成功结果", { page = "mine" })
                    }
                    if (settings.configured() && !notificationsReady) item { PageRow("通知尚未就绪", "前往我的 → 通知与后台运行，修复提醒设置", { page = "mine" }) }
                    if (snapshot.demo) item { PageRow("当前为通知演示", "点击退出演示，恢复真实作业", { settings.demo = false; repository.loadCache(); scope.launch { repository.refresh() } }) }
                    item(key = "work-filters") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("active" to "待完成", "focused" to "重点", "expired" to "已截止", "completed" to "已完成", "ignored" to "已忽略", "other" to "其他").forEach { (key, label) ->
                                FilterChip(selected = selected == key, onClick = { selected = key }, label = { Text(label, fontSize = 12.sp) })
                            }
                        }
                    }
                    item { Row(Modifier.fillMaxWidth()) { Text(when (selected) { "active" -> "当前待办"; "focused" -> "重点任务"; "ignored" -> "已忽略任务"; "expired" -> "已截止未交"; "completed" -> "已提交 / 已完成"; else -> "未开始 / 待确认" }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold); Text("${visible.size} 项", color = Muted, fontSize = 12.sp) } }
                    items(visible, key = { "assignment-${it.id}" }) { AssignmentCard(it, now, hours, repository.tasks) { Notices.reconcile(repository.context, repository.state.value.snapshot) } }
                    if (visible.isEmpty()) item { Text(if (state.refreshing) "正在同步作业…" else if (!settings.configured()) "完成连接后，作业会显示在这里" else "这个分类暂无作业", color = Muted, modifier = Modifier.padding(vertical = 30.dp)) }
                }
                "reminders" -> {
                    item { PageSection("提醒概览") { Text("$urgent 项任务需要关注", color = Mint, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(if (urgent == 0) "当前没有重点或临近截止的任务。" else "确认提交或截止后，下次成功同步会移除提醒。", color = Muted, fontSize = 12.sp) } }
                    item { PageRow("提前提醒时间", "截止前 $hours 小时进入紧急通知", { showHours = true }) }
                    item { PageSection("实时模式") {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(if (live) "实时模式已开启" else "普通后台模式", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Switch(checked = live, enabled = settings.configured(), onCheckedChange = { if (it) startLive() else stopLive() }) }
                        Text(if (live) "每 30 秒读取云端结果，单次最多运行 2 小时。" else "普通模式约 15 分钟调度一次；需要及时跟进时，可开启 2 小时实时模式。", color = Muted, fontSize = 12.sp)
                        if (!settings.configured()) TextButton(onClick = { page = "mine" }) { Text("先前往我的完成连接") }
                    } }
                    item { PageSection("提醒规则") { Text("• 重点与临近截止的任务合并为一条通知\n• 忽略的任务不提醒，可在已忽略中恢复\n• 已提交和已截止的作业不再催促\n• 通知中可选择“稍后 30 分钟”\n• 网络异常时保留上次作业状态", color = Muted, fontSize = 13.sp, lineHeight = 24.sp) } }
                    item { PageSection("同步与通知状态") { Text(if (notificationsReady) "通知与紧急作业渠道已开启" else "通知权限或紧急作业渠道未开启", color = if (notificationsReady) Mint else Coral); Text("云端约每 5 分钟检查学习通，手机读取频率不会改变云端数据更新时间。", color = Muted, fontSize = 12.sp); TextButton(onClick = { page = "mine" }) { Text("前往我的检查系统设置") } } }
                    if (BuildConfig.DEBUG && settings.configured()) item { PageRow("通知效果演示", "仅用于测试，不修改学习通作业", { showDemo = true }) }
                }
                else -> {
                    item { PageSection("账号与连接") { Text("个人测试版", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(if (settings.configured()) "已配置个人云端连接" else "尚未连接云端", color = Muted); Text("本版尚未接入手机号注册和多用户账号。", color = Muted, fontSize = 12.sp) } }
                    item { PageRow("云端连接", if (settings.configured()) "管理服务器地址与访问令牌" else "配置个人测试服务，开始同步作业", { showSettings = true }) }
                    item { Text("应用设置", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    item { PageRow("主题配色", LighthouseThemes.find { it.id == themeId }?.name ?: "薄荷绿 · 深色", { showTheme = true }) }
                    item { Text("系统设置", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                    item { PageRow("通知与后台运行", if (notificationsReady) "通知已开启 · 检查自启动与省电策略" else "通知尚未就绪 · 点击修复", { showGuide = true }) }
                    item { PageRow("小米焦点通知", "试验接入 · 查看设备与平台配置", { showXiaomi = true }) }
                    item { PageRow("隐私与数据说明", "了解账号凭据、云端数据和本机存储", { showPrivacy = true }) }
                    item { PageRow("关于作业灯塔", BuildConfig.VERSION_NAME, { showAbout = true }) }
                }
            }
        }
    }
    if (showAddTask) AddManualTaskDialog({ title, deadline -> repository.tasks.add(title, deadline); selected = "active"; showAddTask = false; Notices.reconcile(repository.context, repository.state.value.snapshot) }, { showAddTask = false })
    if (showSettings) SettingsDialog(repository, { showSettings = false }, { showSettings = false; showGuide = true })
    if (showTheme) ThemePicker(themeId, chooseTheme, { showTheme = false })
    if (showGuide) RunGuide(repository, requestNotification, { showGuide = false })
    if (showHours) ReminderHoursDialog(hours, { value -> settings.urgentHours = value; settings.snoozedUntil = 0; hours = value; Notices.reconcile(repository.context, repository.state.value.snapshot); showHours = false }, { showHours = false })
    if (showAbout) InfoDialog("关于作业灯塔", "版本 ${BuildConfig.VERSION_NAME}\n\n自动汇总学习通作业，临近截止时提醒，确认提交后停止催促。\n\n当前为个人测试版。", { showAbout = false })
    if (showXiaomi) XiaomiFocusDialog(repository.context, { showXiaomi = false })
    if (showPrivacy) InfoDialog("隐私与数据说明", "访问令牌使用 Android Keystore 加密保存在本机，应用不内置学习通密码或令牌。\n\n当前个人云端服务保存你的学习通登录凭据及作业同步结果。手机缓存作业数据，通过 HTTPS 与云端通信。\n\n本版未接入短信注册、广告 SDK 或统计 SDK。", { showPrivacy = false })
    if (showDemo) AlertDialog(onDismissRequest = { showDemo = false }, title = { Text("通知效果演示") }, text = {
        Column { listOf("urgent" to "出现 3 项紧急作业", "submitted_one" to "提交最紧急的一项", "submitted_all" to "全部提交 · 自动撤回", "expired" to "全部截止 · 停止催促").forEach { (key, title) -> TextButton(onClick = { scope.launch { runCatching { repository.scenario(key) }.onFailure { Toast.makeText(repository.context, "演示请求失败，请检查连接", Toast.LENGTH_SHORT).show() } }; showDemo = false }) { Text(title) } } }
    }, confirmButton = { TextButton(onClick = { showDemo = false }) { Text("关闭") } })
}

@Composable
private fun PageSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, color = Muted, fontSize = 12.sp); content() }
}

@Composable
private fun PageRow(title: String, subtitle: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(18.dp)).clickable(onClick = click).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, fontSize = 12.sp) }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}

@Composable
private fun InfoDialog(title: String, message: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = close) { Text("知道了") } })
}

@Composable
private fun ReminderHoursDialog(current: Int, save: (Int) -> Unit, close: () -> Unit) {
    var input by remember { mutableStateOf(current.toString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("提前提醒时间") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("只影响本机何时显示紧急通知，不改变学习通的截止时间。", color = Muted, fontSize = 12.sp)
        OutlinedTextField(input, { input = it.filter(Char::isDigit); error = false }, label = { Text("提前小时数（1～168）") }, singleLine = true, isError = error, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        if (error) Text("请输入 1～168 小时", color = Coral)
    } }, confirmButton = { TextButton(onClick = { val value = input.toIntOrNull(); if (value == null || value !in 1..168) error = true else save(value) }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
private fun AssignmentCard(item: Assignment, now: Long, urgentHours: Int, tasks: LocalTasks, changed: () -> Unit) {
    val urgent = !tasks.ignored(item.id) && item.active(now) && (tasks.focused(item.id) || item.deadline?.let { it - now <= urgentHours * 3600L } == true)
    val accent = if (urgent) Coral else Mint
    Column(Modifier.fillMaxWidth().background(if (urgent) LocalLighthousePalette.current.urgent else Surface, RoundedCornerShape(18.dp)).border(1.dp, if (urgent) Coral.copy(alpha = .2f) else Color.White.copy(alpha = .035f), RoundedCornerShape(18.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.course, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (urgent) Text(if (tasks.focused(item.id)) "重点" else "即将截止", color = Coral, fontSize = 10.sp, modifier = Modifier.background(Coral.copy(alpha = .1f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            TaskActions(item, now, tasks, changed)
        }
        Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 13.dp), lineHeight = 23.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.category == "completed") Icons.Outlined.CheckCircle else Icons.Outlined.Schedule, null, tint = accent, modifier = Modifier.size(15.dp))
            Text("  " + if (tasks.ignored(item.id)) "已忽略 · 不再提醒" else if (item.active(now)) item.deadline?.let { remaining(it - now) } ?: "未设置截止时间" else if (item.category == "expired" || item.category == "active") "已截止 · 不再提醒" else item.rawStatus, color = accent, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (item.stale) Text("待同步", fontSize = 10.sp, color = Coral)
        }
    }
}

@Composable
private fun SettingsDialog(repository: Repository, close: () -> Unit, connected: () -> Unit) {
    val settings = repository.settings
    val scope = repository.scope
    var address by remember { mutableStateOf(settings.endpoint.ifBlank { "https://homework.77777779.xyz" }) }
    var token by remember { mutableStateOf(settings.token) }
        var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!testing) close() }, title = { Text("云端连接") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("输入云端服务地址与专用访问令牌。令牌由服务提供者给你，不是学习通密码。", color = Muted, fontSize = 12.sp)
            OutlinedTextField(address, { address = it }, label = { Text("云端服务地址") }, placeholder = { Text("https://你的服务地址") }, singleLine = true)
            OutlinedTextField(token, { token = it }, label = { Text("访问令牌") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Text("保存前先验证 HTTPS 和令牌；连接失败会保留原设置。不要将令牌发给他人。", color = Muted, fontSize = 11.sp)
            Text("实时模式持续 2 小时；结束后保留系统后台同步。网络异常时保留上次结果。", color = Muted, fontSize = 11.sp)
            if (error != null) Text(error!!, color = Coral, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = {
        val cleanAddress = address.trim().trimEnd('/')
        val parsed = runCatching { java.net.URI(cleanAddress) }.getOrNull()
        val valid = parsed != null && parsed.host != null && parsed.userInfo == null && parsed.rawQuery == null && parsed.rawFragment == null && parsed.path.orEmpty().isEmpty() && (parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2")))
        if (!valid || token.isBlank()) error = "请填写完整 HTTPS 服务地址和访问令牌，地址不要附加 /api 路径"
        else {
            testing = true; error = null
            val testedToken = token.trim()
            scope.launch {
                try {
                    repository.testConnection(cleanAddress, testedToken)
                    if (settings.endpoint != cleanAddress || settings.token != testedToken) {
                        repository.context.deleteFile("snapshot.json")
                        repository.context.deleteFile("demo.json")
                    }
                    settings.endpoint = cleanAddress; settings.token = testedToken
                    settings.demo = false; settings.snoozedUntil = 0
                    repository.loadCache(); repository.context.scheduleSync(); repository.refresh()
                    Toast.makeText(repository.context, "连接成功，请继续检查提醒权限", Toast.LENGTH_LONG).show()
                    connected()
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    error = when (failure) {
                        is javax.net.ssl.SSLException -> "HTTPS 证书校验失败，请检查服务地址、证书和手机时间"
                        is java.net.SocketTimeoutException -> "连接超时，请检查网络或稍后重试"
                        is java.net.UnknownHostException -> "找不到服务器，请检查地址和网络"
                        is java.io.IOException -> "无法连接云端，请检查网络和服务是否在线"
                        else -> if (failure.message?.contains("令牌") == true) "访问令牌无效，请重新复制完整令牌" else "服务响应不正确，请确认填写的是作业服务地址"
                    }
                } finally { testing = false }
            }
        }
    }, enabled = !testing) { Text(if (testing) "正在验证连接…" else "测试并保存") } }, dismissButton = { TextButton(onClick = close, enabled = !testing) { Text("取消") } })
}
