package cn.liuzf.lighthouse

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LocalTasks(context: Context) {
    private val prefs = context.getSharedPreferences("local_tasks", Context.MODE_PRIVATE)
    val revision = MutableStateFlow(0)
    private fun entries(): List<Assignment> {
        val rows = JSONArray(prefs.getString("tasks", "[]"))
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Assignment(row.getString("id"), row.getString("title"), "手动任务", row.getString("category"), row.getString("status"), if (row.isNull("deadline")) null else row.getLong("deadline"), false)
        }
    }
    private fun save(rows: List<Assignment>) {
        val json = JSONArray()
        rows.forEach { json.put(JSONObject().put("id", it.id).put("title", it.title).put("category", it.category).put("status", it.rawStatus).put("deadline", it.deadline ?: JSONObject.NULL)) }
        prefs.edit().putString("tasks", json.toString()).apply()
        revision.value++
    }
    fun add(title: String, deadline: Long?) { save(entries() + Assignment("local:${UUID.randomUUID()}", title, "手动任务", "active", "待完成", deadline, false)) }
    fun complete(id: String, done: Boolean) { save(entries().map { if (it.id == id) it.copy(category = if (done) "completed" else "active", rawStatus = if (done) "已完成" else "待完成") else it }) }
    fun ignored(id: String) = id in prefs.getStringSet("ignored", emptySet())!!
    fun focused(id: String) = id in prefs.getStringSet("focused", emptySet())!!
    fun ignore(id: String, value: Boolean) { change("ignored", id, value) }
    fun focus(id: String, value: Boolean) {
        if (value) change("ignored", id, false)
        change("focused", id, value)
    }
    private fun change(key: String, id: String, value: Boolean) {
        val ids = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        if (value) ids.add(id) else ids.remove(id)
        prefs.edit().putStringSet(key, ids).apply()
        revision.value++
    }
    fun all(snapshot: Snapshot) = if (snapshot.demo) snapshot.items else snapshot.items + entries()
    fun pending(snapshot: Snapshot) = all(snapshot).filter { !ignored(it.id) && it.active(snapshot.now()) }
        .sortedWith(compareBy<Assignment> { !focused(it.id) }.thenBy { it.deadline ?: Long.MAX_VALUE })
    fun reminders(snapshot: Snapshot, hours: Int) = pending(snapshot).filter { focused(it.id) || it.deadline?.let { due -> due - snapshot.now() <= hours * 3600L } == true }
}
