package cn.liuzf.lighthouse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class LighthousePalette(
    val id: String, val name: String, val dark: Boolean,
    val background: Color, val surface: Color, val primary: Color,
    val muted: Color, val accent: Color, val text: Color,
    val hero: List<Color>, val urgent: Color
)

val LighthouseThemes = listOf(
    LighthousePalette("mint", "薄荷绿 · 深色", true, Color(0xFF0D1726), Color(0xFF172335), Color(0xFF6DE2BE), Color(0xFF9AAAC0), Color(0xFFFFBA9C), Color(0xFFF3F6FA), listOf(Color(0xFF20394A), Color(0xFF182C3C)), Color(0xFF302D32)),
    LighthousePalette("blue", "海盐蓝 · 深色", true, Color(0xFF0C1728), Color(0xFF182A42), Color(0xFF91C7FF), Color(0xFFA5B5CC), Color(0xFFFFC08F), Color(0xFFF0F6FF), listOf(Color(0xFF203F61), Color(0xFF172D49)), Color(0xFF352D2C)),
    LighthousePalette("violet", "鸢尾紫 · 深色", true, Color(0xFF191424), Color(0xFF292137), Color(0xFFD3B7FF), Color(0xFFB8A9C8), Color(0xFFFFBEAD), Color(0xFFF8F1FF), listOf(Color(0xFF49335E), Color(0xFF30243F)), Color(0xFF3A2D35)),
    LighthousePalette("amber", "暖杏橙 · 深色", true, Color(0xFF211A17), Color(0xFF342820), Color(0xFFFFC38D), Color(0xFFC5B2A4), Color(0xFFFFACAE), Color(0xFFFFF4E9), listOf(Color(0xFF523C28), Color(0xFF3B2E24)), Color(0xFF432B2F)),
    LighthousePalette("day", "晴日绿 · 浅色", false, Color(0xFFF2F6F5), Color(0xFFFFFFFF), Color(0xFF006A51), Color(0xFF52645F), Color(0xFF9B381E), Color(0xFF152D25), listOf(Color(0xFFD8ECE4), Color(0xFFE7F2ED)), Color(0xFFFFECE3))
)
val LocalLighthousePalette = staticCompositionLocalOf { LighthouseThemes.first() }

@Composable
fun ThemePicker(current: String, choose: (String) -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("主题配色") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选择后立即生效，重新打开应用仍会保留。", style = MaterialTheme.typography.bodySmall)
            LighthouseThemes.forEach { palette ->
                Row(Modifier.fillMaxWidth().clickable { choose(palette.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = current == palette.id, onClick = { choose(palette.id) })
                    Box(Modifier.size(24.dp).background(palette.primary, CircleShape))
                    Text(palette.name, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}
