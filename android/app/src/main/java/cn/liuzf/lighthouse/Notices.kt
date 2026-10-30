package cn.liuzf.lighthouse

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*

object Notices {
    const val TASK_ID = 3101
    const val SERVICE_ID = 3102
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("deadlines", "紧急作业", NotificationManager.IMPORTANCE_HIGH).apply { description = "临近截止的作业，仅新增紧急作业时提醒" })
        manager.createNotificationChannel(NotificationChannel("sync", "实时同步状态", NotificationManager.IMPORTANCE_LOW).apply { description = "用户开启实时模式后的后台同步状态"; setSound(null, null) })
    }
    private fun action(context: Context, name: String, code: Int) = PendingIntent.getBroadcast(context, code, Intent(context, NotificationActions::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun open(context: Context) = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun canNotify(context: Context) = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    @Synchronized fun reconcile(context: Context, snapshot: Snapshot) {
        val settings = context.repo().settings
        val now = snapshot.now()
        val urgent = context.repo().tasks.reminders(snapshot, settings.urgentHours)
        val manager = NotificationManagerCompat.from(context)
        if (urgent.isEmpty() || settings.snoozedUntil > System.currentTimeMillis() / 1000) {
            manager.cancel(TASK_ID)
            if (urgent.isEmpty()) settings.prefs.edit().remove("notified_ids").apply()
            return
        }
        if (!canNotify(context)) return
        val prefix = if (snapshot.demo) "演示 · " else ""
        val expiredSync = urgent.any { !it.id.startsWith("local:") } && (snapshot.error != null || now - snapshot.lastSuccess > 1800)
        val previous = settings.prefs.getStringSet("notified_ids", emptySet()) ?: emptySet()
        val current = urgent.map { it.id }.toSet()
        val audible = urgent.any { it.id !in previous && (it.id.startsWith("local:") || !expiredSync) }
        val primary = urgent.filter { it.deadline != null }.minByOrNull { it.deadline!! } ?: urgent.first()
        val display = listOf(primary) + urgent.filter { it.id != primary.id }
        val targetTime = primary.deadline?.let { (it - snapshot.serverOffset) * 1000 }
        val views = android.widget.RemoteViews(context.packageName, R.layout.notification_countdowns)
        val rows = listOf(Triple(R.id.title_one, R.id.timer_one, R.id.detail_one), Triple(R.id.title_two, R.id.timer_two, R.id.detail_two))
        display.take(2).forEachIndexed { i, item ->
            val (titleId, timerId, detailId) = rows[i]
            views.setTextViewText(titleId, item.title)
            views.setTextViewText(detailId, item.course + if (item.deadline == null) " · 重点任务" else " · 距截止")
            if (item.deadline != null) {
                views.setChronometer(timerId, android.os.SystemClock.elapsedRealtime() + (item.deadline - snapshot.serverOffset) * 1000 - System.currentTimeMillis(), null, true)
                views.setChronometerCountDown(timerId, true)
            } else views.setViewVisibility(timerId, android.view.View.GONE)
        }
        if (display.size < 2) views.setViewVisibility(R.id.task_two, android.view.View.GONE)
        views.setTextViewText(R.id.more_tasks, if (display.size > 2) "另有 ${display.size - 2} 项，点击查看全部" else "")
        val builder = NotificationCompat.Builder(context, "deadlines")
            .setSmallIcon(R.drawable.ic_lighthouse).setColor(0xFF38CFA8.toInt())
            .setContentTitle("${prefix}${urgent.size} 项任务待完成")
            .setContentText("${primary.course} · ${primary.title}")
            .setSubText(if (expiredSync) "状态待同步" else if (targetTime != null) "距最近截止" else "重点任务")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomBigContentView(views)
            .setContentIntent(open(context)).setOngoing(true).setAutoCancel(false)
            .setOnlyAlertOnce(!audible).setSilent(!audible).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "查看作业", open(context)).addAction(0, "稍后 30 分钟", action(context, "snooze", 1)).addAction(0, "立即同步", action(context, "refresh", 2))
        if (targetTime != null) builder.setWhen(targetTime).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        else builder.setShowWhen(false)
        val notification = builder.build()
        XiaomiFocus.attach(context, notification, primary, urgent.size, targetTime)
        manager.notify(TASK_ID, notification)
        settings.prefs.edit().putStringSet("notified_ids", current).apply()
    }
    fun service(context: Context): Notification = NotificationCompat.Builder(context, "sync")
        .setSmallIcon(R.drawable.ic_lighthouse).setContentTitle("作业灯塔 · 实时模式")
        .setContentText("约 30 秒检查一次云端结果 · 2 小时后自动结束")
        .setContentIntent(open(context)).setOngoing(true).setSilent(true)
        .addAction(0, "结束实时模式", action(context, "stop_live", 3)).build()
}

fun remaining(seconds: Long): String {
    val minutes = ((seconds.coerceAtLeast(0) + 59) / 60)
    return when {
        minutes < 60 -> "剩余 ${minutes} 分钟"
        minutes < 1440 -> "剩余 ${minutes / 60} 小时 ${minutes % 60} 分钟"
        else -> "剩余 ${minutes / 1440} 天 ${minutes % 1440 / 60} 小时"
    }
}

class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "snooze" -> {
                context.repo().settings.snoozedUntil = System.currentTimeMillis() / 1000 + 1800
                NotificationManagerCompat.from(context).cancel(Notices.TASK_ID)
            }
            "refresh" -> WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
            "stop_live" -> context.stopService(Intent(context, LiveSyncService::class.java))
        }
    }
}

class LiveSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notices.SERVICE_ID, Notices.service(this), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notices.SERVICE_ID, Notices.service(this))
        repo().settings.prefs.edit().putBoolean("live", true).apply()
        scope.launch {
            val end = android.os.SystemClock.elapsedRealtime() + 2 * 60 * 60 * 1000
            while (isActive && android.os.SystemClock.elapsedRealtime() < end) { repo().refresh(); delay(30_000) }
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        scope.cancel(); repo().settings.prefs.edit().putBoolean("live", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
