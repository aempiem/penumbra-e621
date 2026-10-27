package app.e6reels.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

/**
 * Filter chip whose selected state is unmistakable at a glance: pink outline, pink label and a check,
 * instead of a barely different dark fill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF3A1D26),
            selectedLabelColor = Accent,
            selectedLeadingIconColor = Accent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color(0x55FFFFFF),
            selectedBorderColor = Accent,
        ),
    )
}

/**
 * A bottom sheet opens in its own window, which doesn't inherit the hidden status bar, so the bar would pop
 * up every time one opens. Call this inside the sheet's content to hide it there too.
 */
@Composable
fun HideStatusBarInSheet() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val bars = WindowCompat.getInsetsController(window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.statusBars())
    }
}

/** One choice out of a few, as equal-width segments in a single row: aligned, never wrapping. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    SegmentRow(options, { it == selected }, { onSelect(it) }, Role.RadioButton, modifier)
}

/** Several independent on/off options, as equal-width segments in a single row. */
@Composable
fun SegmentedMulti(options: List<String>, checked: List<Boolean>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    SegmentRow(options, { checked[it] }, onToggle, Role.Checkbox, modifier)
}

@Composable
private fun SegmentRow(
    options: List<String>,
    isOn: (Int) -> Boolean,
    onClick: (Int) -> Unit,
    role: Role,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .border(1.dp, Color(0x40FFFFFF), shape),
    ) {
        options.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x40FFFFFF)))
            val on = isOn(i)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on) Color(0xFF3A1D26) else Color.Transparent)
                    .selectable(selected = on, role = role, onClick = { onClick(i) })
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) Accent else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A labelled section of a settings sheet: muted label on the left margin, the control full width below. */
@Composable
fun SettingRow(label: String, value: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xAAFFFFFF), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        content()
    }
}
