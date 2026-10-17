package ru.vasev.glasses.debug

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.vasev.glasses.R
import ru.vasev.glasses.ui.theme.GlassesTheme

/**
 * Только debug: витрина вариантов иконок. Нажатие на вариант — выбор; он пишется в лог `Showcase`
 * и в SharedPreferences `showcase`. Запуск: `adb shell am start -n ru.vasev.glasses/.debug.ShowcaseActivity`.
 */
class ShowcaseActivity : ComponentActivity() {

    private data class Option(val id: String, @DrawableRes val icon: Int)
    private data class Slot(val id: String, val title: String, val options: List<Option>)
    private data class AppIcon(val id: String, val title: String, @DrawableRes val fg: Int, @DrawableRes val mono: Int, val bg: Color)

    private val slots = listOf(
        Slot("tab_assistant", "Assistant tab", listOf(o("assistant", R.drawable.ic_sym_assistant), o("smart_toy", R.drawable.ic_sym_smart_toy), o("forum", R.drawable.ic_sym_forum))),
        Slot("tab_translate", "Translate tab", listOf(o("translate", R.drawable.ic_sym_translate), o("g_translate", R.drawable.ic_sym_g_translate), o("language", R.drawable.ic_sym_language))),
        Slot("tab_settings", "Settings tab (fixed)", listOf(o("settings", R.drawable.ic_sym_settings))),
        Slot("ask", "Ask (mic button)", listOf(o("mic", R.drawable.ic_sym_mic), o("keyboard_voice", R.drawable.ic_sym_keyboard_voice), o("record_voice_over", R.drawable.ic_sym_record_voice_over))),
        Slot("what_i_see", "What do I see", listOf(o("visibility", R.drawable.ic_sym_visibility), o("eyeglasses", R.drawable.ic_sym_eyeglasses), o("photo_camera", R.drawable.ic_sym_photo_camera))),
        Slot("new_conversation", "New conversation", listOf(o("add_comment", R.drawable.ic_sym_add_comment), o("restart_alt", R.drawable.ic_sym_restart_alt), o("edit_square", R.drawable.ic_sym_edit_square))),
        Slot("partner_speaks", "Partner speaks", listOf(o("hearing", R.drawable.ic_sym_hearing), o("record_voice_over", R.drawable.ic_sym_record_voice_over), o("person", R.drawable.ic_sym_person))),
        Slot("me_reply", "I reply", listOf(o("mic", R.drawable.ic_sym_mic), o("reply", R.drawable.ic_sym_reply), o("campaign", R.drawable.ic_sym_campaign))),
        Slot("stop", "Stop", listOf(o("stop", R.drawable.ic_sym_stop), o("stop_circle", R.drawable.ic_sym_stop_circle), o("close", R.drawable.ic_sym_close))),
        Slot("download_voice", "Download voice", listOf(o("download", R.drawable.ic_sym_download), o("download_for_offline", R.drawable.ic_sym_download_for_offline), o("cloud_download", R.drawable.ic_sym_cloud_download))),
        Slot("clear_feed", "Clear feed", listOf(o("delete_sweep", R.drawable.ic_sym_delete_sweep), o("clear_all", R.drawable.ic_sym_clear_all), o("delete", R.drawable.ic_sym_delete))),
        Slot("connect_glasses", "Connect glasses", listOf(o("bluetooth_connected", R.drawable.ic_sym_bluetooth_connected), o("link", R.drawable.ic_sym_link), o("eyeglasses", R.drawable.ic_sym_eyeglasses))),
        Slot("show_partner", "Show to partner (large translation)", listOf(o("fullscreen", R.drawable.ic_sym_fullscreen), o("visibility", R.drawable.ic_sym_visibility), o("volume_up", R.drawable.ic_sym_volume_up))),
        Slot("replace_key", "Replace key", listOf(o("key", R.drawable.ic_sym_key), o("edit", R.drawable.ic_sym_edit))),
        Slot("delete_key", "Delete key", listOf(o("delete", R.drawable.ic_sym_delete), o("close", R.drawable.ic_sym_close))),
    )

    private val appIcons = listOf(
        AppIcon("A", "A - glasses + spark", R.drawable.showcase_icon_a_fg, R.drawable.showcase_icon_a_mono, Color(0xFF0B57D0)),
        AppIcon("B", "B - speech bubble with glasses", R.drawable.showcase_icon_b_fg, R.drawable.showcase_icon_b_mono, Color(0xFF00696B)),
        AppIcon("C", "C - round glasses, spark glint", R.drawable.showcase_icon_c_fg, R.drawable.showcase_icon_c_mono, Color(0xFF6750A4)),
    )

    private fun o(id: String, @DrawableRes icon: Int) = Option(id, icon)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContent {
            GlassesTheme {
                val chosen = remember {
                    mutableStateMapOf<String, String>().apply {
                        prefs.all.forEach { (k, v) -> if (v is String) put(k, v) }
                    }
                }
                fun choose(slot: String, option: String) {
                    chosen[slot] = option
                    prefs.edit().putString(slot, option).apply()
                    Log.d(TAG, "choice $slot=$option")
                    Log.d(TAG, "result ${chosen.toSortedMap()}")
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            Text("Icon showcase (debug)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Tap the option you like - the choice is saved and logged.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item { SectionTitle("App icon: circle · rounded · themed (monochrome)") }
                        items(appIcons) { icon ->
                            AppIconRow(icon, selected = chosen["app_icon"] == icon.id) { choose("app_icon", icon.id) }
                        }
                        item { SectionTitle("Tab and button icons") }
                        items(slots) { slot ->
                            SlotRow(slot, chosen[slot.id]) { choose(slot.id, it) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun AppIconRow(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Choose option ${icon.id}", onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(icon.title + if (selected) "  ✓ chosen" else "", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(icon.bg)) {
                    Image(painterResource(icon.fg), contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(icon.bg)) {
                    Image(painterResource(icon.fg), contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                // Тематическая иконка Android: монохромный слой, окрашенный системой.
                Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
                    Image(
                        painterResource(icon.mono),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @Composable
    private fun SlotRow(slot: Slot, selected: String?, onChoose: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(slot.title, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                slot.options.forEach { option ->
                    val isSelected = option.id == selected
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .border(if (isSelected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (isSelected) 1f else 0f), RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = "Choose ${option.id}") { onChoose(option.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painterResource(option.icon),
                            contentDescription = option.id,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(option.id, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Showcase"
        const val PREFS = "showcase"
    }
}
