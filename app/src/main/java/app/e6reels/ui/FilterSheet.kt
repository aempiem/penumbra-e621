package app.e6reels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.e6reels.data.ALL_RATINGS
import app.e6reels.data.FeedSettings
import app.e6reels.data.MediaMode
import app.e6reels.data.Sort
import app.e6reels.data.VideoQuality
import kotlin.math.roundToInt

/** Global filters that apply on top of whichever list is active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    initial: FeedSettings,
    onApply: (FeedSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Surface1) {
        HideStatusBarInSheet()
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, color = Color.White)
                TextButton(
                    onClick = { draft = FeedSettings(activeListId = draft.activeListId) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text("Reset") }
            }

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                SettingRow("Sort") {
                    Segmented(
                        listOf("Random", "Newest", "Top", "Trending"),
                        Sort.entries.indexOf(draft.sort),
                        { draft = draft.copy(sort = Sort.entries[it]) },
                    )
                }
                SettingRow("Media") {
                    Segmented(
                        listOf("Everything", "Videos & GIFs", "Images"),
                        MediaMode.entries.indexOf(draft.media),
                        { draft = draft.copy(media = MediaMode.entries[it]) },
                    )
                }
                SettingRow("Video quality") {
                    Segmented(
                        VideoQuality.entries.map { it.label },
                        VideoQuality.entries.indexOf(draft.videoQuality),
                        { draft = draft.copy(videoQuality = VideoQuality.entries[it]) },
                    )
                }
                SettingRow("Rating") {
                    SegmentedMulti(
                        ALL_RATINGS.map { it.second },
                        ALL_RATINGS.map { it.first in draft.ratings },
                        { i ->
                            val code = ALL_RATINGS[i].first
                            val next = if (code in draft.ratings) draft.ratings - code else draft.ratings + code
                            if (next.isNotEmpty()) draft = draft.copy(ratings = next)
                        },
                    )
                }
                SettingRow("Minimum score", value = "${draft.minScore}") {
                    Slider(
                        value = draft.minScore.toFloat(),
                        onValueChange = { draft = draft.copy(minScore = (it / 10).roundToInt() * 10) },
                        valueRange = 0f..500f,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .toggleable(value = draft.hideSeen, role = Role.Switch, onValueChange = { draft = draft.copy(hideSeen = it) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Hide seen posts", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = draft.hideSeen, onCheckedChange = null)
                }
            }

            Button(
                onClick = {
                    onApply(draft)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text("Apply") }
        }
    }
}
