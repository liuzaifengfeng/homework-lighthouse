package cn.liuzf.lighthouse

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RunSupport {
    fun channelEnabled(context: Context, id: String = "deadlines"): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(id) ?: return false
        val group = if (Build.VERSION.SDK_INT >= 28) channel.group?.let { manager.getNotificationChannelGroup(it) } else null
        return Notices.canNotify(context) && channel.importance != NotificationManager.IMPORTANCE_NONE && group?.isBlocked != true
    }
    fun batteryAllowed(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun open(context: Context, kind: String) {
        val fallback = Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val target = when (kind) {
            "notifications" -> Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
            "channel" -> Intent(SystemSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName).putExtra(SystemSettings.EXTRA_CHANNEL_ID, "deadlines")
            "battery" -> Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "autostart" -> Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            else -> fallback
        }
        if (runCatching { context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
            runCatching { context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Toast.makeText(context, "此系统不支持直达入口，请在应用信息中查找对应设置", Toast.LENGTH_LONG).show()
        }
    }
    fun testNotification(context: Context): Boolean {
        if (!channelEnabled(context)) return false
        NotificationManagerCompat.from(context).notify(3199, NotificationCompat.Builder(context, "deadlines")
            .setSmallIcon(R.drawable.ic_lighthouse).setContentTitle("作业灯塔 · 通知测试")
            .setContentText("看到这条说明通知可达；测试通知 1 分钟后自动消失。")
            .setStyle(NotificationCompat.BigTextStyle().bigText("这是一条本地测试通知，不代表真实作业。请锁屏检查通知是否可见。能看到通知不代表后台已获准运行。"))
            .setAutoCancel(true).setTimeoutAfter(60_000).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        return true
    }
}

@Composable
fun RunGuide(repository: Repository, requestNotification: () -> Unit, close: () -> Unit) {
    val context = repository.context
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { revision++; delay(2000) } }
    }
    val notify = remember(revision) { RunSupport.channelEnabled(context) }
    val battery = remember(revision) { RunSupport.batteryAllowed(context) }
    val live = repository.settings.prefs.getBoolean("live", false)
    val contact = repository.settings.prefs.getLong("last_contact", 0)
    val xiaomi = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")
    AlertDialog(onDismissRequest = close, title = { Text("提醒与后台运行") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("先让通知可见，再检查后台限制。返回应用后会重新检测可读取的状态。", style = MaterialTheme.typography.bodySmall)
            GuideItem("1 · 通知与紧急作业渠道", if (notify) "已允许发送；声音、横幅和锁屏显示由系统设置决定。" else "尚未允许或渠道被关闭，紧急作业将无法显示。") {
                TextButton(onClick = requestNotification) { Text("允许通知") }
                TextButton(onClick = { RunSupport.open(context, "channel") }) { Text("通知设置") }
            }
            GuideItem("2 · 电池优化", if (battery) "系统电池优化：已豁免。厂商的省电策略仍需单独检查。" else "系统电池优化：未豁免。后台同步可能延迟；此项由你选择是否调整。") {
                TextButton(onClick = { RunSupport.open(context, "battery") }) { Text("电池优化设置") }
            }
            GuideItem(if (xiaomi) "3 · 小米 / 澎湃 OS 设置" else "3 · 厂商后台设置", "建议允许自启动，并在应用信息 → 省电策略中选择“无限制”。不同系统菜单名称可能不同。") {
                TextButton(onClick = { RunSupport.open(context, "autostart") }) { Text("自启动设置") }
                TextButton(onClick = { RunSupport.open(context, "details") }) { Text("应用信息") }
            }
            Text("自启动、省电策略和最近任务锁定无法可靠自动检测，不会显示成“已开启”。在最近任务中长按作业灯塔卡片，查找锁定选项；清理后台时保留它。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(if (live) "当前：实时模式运行中，每 30 秒获取云端结果，单次最多 2 小时。" else "当前：普通后台模式，计划约 15 分钟获取一次，系统省电时可能更晚。")
            Text("云端约每 5 分钟检查学习通。手机拉取更频繁不等于学习通立即更新。实时模式结束后退回普通模式，静默的同步通知与作业提醒是两条独立通知。", style = MaterialTheme.typography.bodySmall)
            Text(if (contact > 0) "手机上次连通云端：" + SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(contact * 1000)) else "手机尚未成功连通云端。", style = MaterialTheme.typography.bodySmall)
            Text("Android 15 会限制数据同步前台服务的累计后台时长。强行停止应用后需手动重新打开；后台设置能减少延迟，无法保证永久驻留。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { feedback = if (RunSupport.testNotification(context)) "已发送，请下拉通知栏或锁屏检查。" else "通知未开启，请先完成第 1 步。" }) { Text("发送测试通知") }
            if (feedback.isNotBlank()) Text(feedback, color = MaterialTheme.colorScheme.primary)
            Text("真机验收：保存连接 → 允许通知 → 设置后台运行 → 开启实时模式 → 锁屏等待 → 提交真实作业后确认提醒撤回。若当前没有紧急作业，可先将提前提醒改为 72 小时。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable
private fun GuideItem(title: String, description: String, actions: @Composable RowScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(description, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), content = actions)
}
