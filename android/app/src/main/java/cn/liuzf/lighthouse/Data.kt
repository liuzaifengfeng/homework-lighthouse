package cn.liuzf.lighthouse

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Assignment(val id: String, val title: String, val course: String, val category: String, val rawStatus: String, val deadline: Long?, val stale: Boolean) {
    fun active(now: Long) = category == "active" && (if (deadline == null) id.startsWith("local:") else deadline > now)
}
data class Snapshot(val items: List<Assignment> = emptyList(), val version: Long = 0, val lastSuccess: Long = 0, val serverOffset: Long = 0, val error: String? = null, val demo: Boolean = false) {
    fun now() = System.currentTimeMillis() / 1000 + serverOffset
    fun active() = items.filter { it.active(now()) }.sortedBy { it.deadline }
}
data class ScreenState(val snapshot: Snapshot = Snapshot(), val refreshing: Boolean = false, val networkError: String? = null)

class Settings(val context: Context) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) { prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("lighthouse_token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("lighthouse_token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    var token: String
        get() = runCatching {
            val encoded = prefs.getString("token", "") ?: ""
            if (encoded.isEmpty()) "" else {
                val parts = encoded.split(":")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            prefs.edit().putString("token", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
        }
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(value) { prefs.edit().putBoolean("demo", value).apply() }
    var urgentHours: Int
        get() = prefs.getInt("urgentHours", 24)
        set(value) { prefs.edit().putInt("urgentHours", value.coerceIn(1, 168)).apply() }
    var snoozedUntil: Long
        get() = prefs.getLong("snoozedUntil", 0)
        set(value) { prefs.edit().putLong("snoozedUntil", value).apply() }
    fun configured() = endpoint.isNotBlank() && token.isNotBlank()
}

class Repository(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = Settings(context)
    val tasks = LocalTasks(context)
    val state = MutableStateFlow(ScreenState())
    private val mutex = Mutex()
    init { loadCache() }
    fun loadCache() {
        val file = java.io.File(context.filesDir, if (settings.demo) "demo.json" else "snapshot.json")
        state.value = ScreenState(runCatching { parse(JSONObject(file.readText()), false) }.getOrDefault(Snapshot(demo = settings.demo)))
    }
    private fun parse(json: JSONObject, fromNetwork: Boolean): Snapshot {
        val items = json.getJSONArray("items")
        val rows = (0 until items.length()).map {
            val row = items.getJSONObject(it)
            Assignment(row.getString("id"), row.getString("title"), row.getString("course_name"), row.getString("category"), row.getString("raw_status"), if (row.isNull("deadline_at")) null else row.getLong("deadline_at"), row.has("stale_reason"))
        }
        val offset = if (fromNetwork) json.getLong("server_time") - System.currentTimeMillis() / 1000 else json.optLong("client_server_offset", 0)
        return Snapshot(rows, json.getLong("version"), json.optLong("last_success", 0), offset, if (json.isNull("sync_error")) null else json.optString("sync_error"), json.optBoolean("demo"))
    }
    private fun request(path: String, payload: JSONObject? = null, endpoint: String = settings.endpoint, token: String = settings.token): JSONObject {
        val address = URL(endpoint + path)
        require(address.protocol == "https" || address.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) { "请使用 HTTPS 地址" }
        val connection = address.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            if (connection.responseCode == 401) error("访问令牌无效，请检查连接设置")
            if (connection.responseCode !in 200..299) error("服务器暂时不可用（${connection.responseCode}）")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    suspend fun testConnection(endpoint: String, token: String): Int = withContext(Dispatchers.IO) {
        val json = request("/api/v1/snapshot", endpoint = endpoint, token = token)
        require(!json.optBoolean("demo")) { "请连接真实作业服务" }
        val snapshot = parse(json, true)
        snapshot.items.size
    }
    suspend fun refresh(triggerCloud: Boolean = false): Boolean = mutex.withLock {
        if (!settings.configured()) return@withLock false
        val requestDemo = settings.demo
        state.value = state.value.copy(refreshing = true)
        try {
            val json = withContext(Dispatchers.IO) {
                val accepted = triggerCloud && !requestDemo && request("/api/v1/sync", JSONObject()).optBoolean("accepted")
                if (accepted) delay(500)
                var response = request(if (requestDemo) "/api/v1/demo/snapshot" else "/api/v1/snapshot")
                var attempts = 0
                while (accepted && response.optBoolean("sync_running") && attempts++ < 25) {
                    delay(1000)
                    response = request("/api/v1/snapshot")
                }
                response
            }
            if (settings.demo != requestDemo) { state.value = state.value.copy(refreshing = false); return@withLock false }
            val snap = parse(json, true)
            json.put("client_server_offset", snap.serverOffset)
            withContext(Dispatchers.IO) {
                val file = android.util.AtomicFile(java.io.File(context.filesDir, if (requestDemo) "demo.json" else "snapshot.json"))
                val stream = file.startWrite()
                try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
            }
            state.value = ScreenState(snap)
            settings.prefs.edit().putLong("last_contact", System.currentTimeMillis() / 1000).apply()
            Notices.reconcile(context, snap)
            true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) { state.value = state.value.copy(refreshing = false); throw error }
            state.value = state.value.copy(snapshot = state.value.snapshot.copy(error = "client_network_error"), refreshing = false, networkError = if (error is java.io.IOException) "网络暂时不可用，保留上次同步结果" else error.message?.take(80) ?: "同步失败")
            Notices.reconcile(context, state.value.snapshot)
            false
        }
    }
    suspend fun scenario(name: String) {
        withContext(Dispatchers.IO) { request("/api/v1/demo/scenario", JSONObject().put("name", name)) }
        settings.demo = true
        refresh()
    }
}

class LighthouseApp : Application() {
    lateinit var repository: Repository
    override fun onCreate() {
        super.onCreate(); repository = Repository(this); repository.settings.prefs.edit().putBoolean("live", false).apply(); Notices.channels(this)
        scheduleSync()
    }
}
fun Context.repo() = (applicationContext as LighthouseApp).repository
fun Context.scheduleSync() {
    // The private test transport is adb reverse on loopback, independent of Wi-Fi validation.
    // Public HTTPS endpoints retain the normal CONNECTED constraint.
    val localTunnel = runCatching { URL(repo().settings.endpoint).host in setOf("127.0.0.1", "localhost", "10.0.2.2") }.getOrDefault(false)
    val constraints = Constraints.Builder().setRequiredNetworkType(if (localTunnel) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build()
    val job = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
    WorkManager.getInstance(this).enqueueUniquePeriodicWork("lighthouse_sync", ExistingPeriodicWorkPolicy.UPDATE, job)
    WorkManager.getInstance(this).enqueueUniquePeriodicWork("lighthouse_local_tasks", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<LocalTaskWorker>(15, TimeUnit.MINUTES).build())
}
class LocalTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Notices.reconcile(applicationContext, applicationContext.repo().state.value.snapshot)
        return Result.success()
    }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!applicationContext.repo().settings.configured()) { Notices.reconcile(applicationContext, applicationContext.repo().state.value.snapshot); return Result.success() }
        return if (applicationContext.repo().refresh()) Result.success() else Result.retry()
    }
}
