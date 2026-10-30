package cn.liuzf.lighthouse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Debug APK only; absent from the release manifest and sources. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = context.repo()
                when (intent.getStringExtra("command")) {
                    "configure" -> {
                        repo.settings.endpoint = intent.getStringExtra("endpoint") ?: ""
                        repo.settings.token = intent.getStringExtra("token") ?: ""
                        repo.settings.demo = false
                        context.scheduleSync()
                        repo.refresh()
                    }
                    "scenario" -> { repo.settings.snoozedUntil = 0; repo.scenario(intent.getStringExtra("name") ?: "urgent") }
                    "real" -> { repo.settings.demo = false; repo.loadCache(); repo.refresh() }
                    "sync" -> repo.refresh()
                    "live" -> ContextCompat.startForegroundService(context, Intent(context, LiveSyncService::class.java))
                    "stop_live" -> context.stopService(Intent(context, LiveSyncService::class.java))
                    "snooze" -> context.sendBroadcast(Intent(context, NotificationActions::class.java).setAction("snooze"))
                    "worker_sync" -> context.sendBroadcast(Intent(context, NotificationActions::class.java).setAction("refresh"))
                }
                Log.i("LighthouseTest", "command_done command=${intent.getStringExtra("command")} version=${repo.state.value.snapshot.version} active=${repo.state.value.snapshot.active().size} network_error=${repo.state.value.networkError != null}")
            } catch (e: Exception) { Log.e("LighthouseTest", "command_failed ${e.javaClass.simpleName}") }
            finally { pending.finish() }
        }
    }
}
