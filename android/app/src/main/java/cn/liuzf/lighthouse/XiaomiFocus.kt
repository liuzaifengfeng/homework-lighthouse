package cn.liuzf.lighthouse

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Official client extension schema. Xiaomi must authorize the app/device to render it. */
object XiaomiFocus {
    fun attach(context: Context, notification: Notification, item: Assignment, count: Int, targetTime: Long?) {
        val iconKey = "miui.focus.pic_lighthouse"
        val icon = JSONObject().put("type", 1).put("pic", iconKey)
        val timer = targetTime?.let { JSONObject().put("timerType", -1).put("timerWhen", it).put("timerSystemCurrent", System.currentTimeMillis()) }
        val highlight = JSONObject().put("picFunction", iconKey).put("content", "距截止").put("subContent", "$count 项任务待完成")
        val digits = JSONObject().put("content", "截止").put("showHighlightColor", true)
        if (timer != null) { highlight.put("timerInfo", timer); digits.put("timerInfo", timer) }
        else { highlight.put("title", "重点"); digits.put("digit", count.toString()).put("content", "待办") }
        val params = JSONObject().put("protocol", 1).put("business", "homework_reminder")
            .put("updatable", true).put("reopen", "reopen").put("enableFloat", false).put("islandFirstFloat", false)
            .put("filterWhenNoPermission", false).put("ticker", "作业灯塔 · $count 项待办").put("aodTitle", item.title)
            .put("baseInfo", JSONObject().put("type", 1).put("title", item.title).put("content", item.course))
            .put("highlightInfo", highlight)
            .put("param_island", JSONObject().put("islandProperty", 1).put("highlightColor", "#38CFA8")
                .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", icon)
                    .put("textInfo", JSONObject().put("title", "作业").put("content", "待交")))
                    .put("sameWidthDigitInfo", digits))
                .put("smallIslandArea", JSONObject().put("picInfo", icon)))
        notification.extras.putString("miui.focus.param", JSONObject().put("param_v2", params).toString())
        notification.extras.putBundle("miui.focus.pics", Bundle().apply { putParcelable(iconKey, Icon.createWithResource(context, R.drawable.ic_lighthouse)) })
    }
}

@Composable
fun XiaomiFocusDialog(context: Context, close: () -> Unit) {
    val protocol = android.provider.Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
    AlertDialog(onDismissRequest = close, title = { Text("小米焦点通知 · 试验接入") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前设备焦点通知协议：$protocol")
            Text(if (BuildConfig.XIAOMI_APP_ID.isEmpty()) "小米 App ID：尚未配置" else "小米 App ID：已配置")
            Text("通知已附带官方焦点通知 / 超级岛扩展数据及计时组件。实际展示需要小米平台开通应用权限，或将测试手机加入设备白名单。")
            Text("普通 Android 模拟器只验证原生倒计时和数据结构，不能验证小米系统渲染。")
            Text("获得小米 App ID 后，可在构建时传入 -PxiaomiAppId=你的ID，并在小米平台登记本应用签名。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("知道了") } })
}
