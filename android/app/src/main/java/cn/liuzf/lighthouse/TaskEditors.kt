package cn.liuzf.lighthouse

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun AddManualTaskDialog(save: (String, Long?) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf<Long?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("添加手动任务") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("任务内容") }, modifier = Modifier.fillMaxWidth())
            Text("仅保存在这台手机上，不会被学习通同步覆盖。", style = MaterialTheme.typography.bodySmall)
            Text(deadline?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it * 1000)) } ?: "未设置截止时间")
            Row {
                TextButton(onClick = {
                    val date = Calendar.getInstance()
                    deadline?.let { date.timeInMillis = it * 1000 }
                    DatePickerDialog(context, { _, year, month, day ->
                        TimePickerDialog(context, { _, hour, minute ->
                            date.set(year, month, day, hour, minute, 0)
                            deadline = date.timeInMillis / 1000
                        }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), true).show()
                    }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("设置截止时间") }
                if (deadline != null) TextButton(onClick = { deadline = null }) { Text("清除") }
            }
            Text("有截止时间时按提前提醒规则显示；加入重点后立即进入关注通知。没有截止时间也可以加入重点。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { save(title, deadline) }) { Text("添加") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
fun TaskActions(item: Assignment, now: Long, tasks: LocalTasks, changed: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun update(action: () -> Unit) { action(); changed(); expanded = false }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, "任务操作：${item.title}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (item.active(now)) DropdownMenuItem(text = { Text(if (tasks.focused(item.id)) "移出重点" else "提上日程") }, onClick = { update { tasks.focus(item.id, !tasks.focused(item.id)) } })
            DropdownMenuItem(text = { Text(if (tasks.ignored(item.id)) "恢复关注" else "忽略不管") }, onClick = { update { tasks.ignore(item.id, !tasks.ignored(item.id)) } })
            if (item.id.startsWith("local:")) DropdownMenuItem(text = { Text(if (item.category == "completed") "重新打开" else "标记完成") }, onClick = { update { tasks.complete(item.id, item.category != "completed") } })
        }
    }
}
