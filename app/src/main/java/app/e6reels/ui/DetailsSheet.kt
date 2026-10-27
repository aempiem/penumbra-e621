package app.e6reels.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.e6reels.data.Post
import app.e6reels.data.TagMark

/**
 * The post's "description", like an Instagram reel's: who made it and every tag, with each tag a three-state
 * chip (tap: include in the feed, tap again: block, again: clear). Changes are collected here and applied
 * once when the sheet closes, so the feed restarts a single time instead of after every tap.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PostDetailsSheet(
    post: Post,
    markOf: (String) -> TagMark,
    onApply: (Map<String, TagMark>) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(post.id) { (post.tags + post.artists).distinct().associateWith(markOf) }
    val marks = remember(post.id) { mutableStateMapOf<String, TagMark>().apply { putAll(initial) } }

    fun close() {
        val changes = marks.filter { (tag, mark) -> initial[tag] != mark }
        if (changes.isNotEmpty()) onApply(changes.toMap())
        onDismiss()
    }

    val general = post.tags.filter { it !in post.keyTags && it !in post.artists }

    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
    ) {
        HideStatusBarInSheet()
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                if (post.artists.isNotEmpty()) post.artists.joinToString(", ") { "@$it" } else "Details",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))

            TagGroup("Artist", post.artists, marks)
            TagGroup("Characters & species", post.keyTags, marks)
            TagGroup("General", general, marks)

            OutlinedButton(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                Text("  Save to phone")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagGroup(title: String, tags: List<String>, marks: MutableMap<String, TagMark>) {
    if (tags.isEmpty()) return
    Text(title, color = Color(0xAAFFFFFF), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            val mark = marks[tag] ?: TagMark.NONE
            MarkChip(tag, mark) {
                marks[tag] = when (mark) {
                    TagMark.NONE -> TagMark.INCLUDE
                    TagMark.INCLUDE -> TagMark.BLOCK
                    TagMark.BLOCK -> TagMark.NONE
                }
            }
        }
    }
}

@Composable
private fun MarkChip(tag: String, mark: TagMark, onClick: () -> Unit) {
    val danger = MaterialTheme.colorScheme.error
    val (border, container, content) = when (mark) {
        TagMark.NONE -> Triple(Color(0x55FFFFFF), Color.Transparent, Color.White)
        TagMark.INCLUDE -> Triple(Accent, Color(0xFF3A1D26), Accent)
        TagMark.BLOCK -> Triple(danger, Color(0x22FF6B4A), danger)
    }
    // the visible chip is 36 dp; the touch area is padded up to 48 dp around it
    Box(Modifier.minimumInteractiveComponentSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            // min width = the touch size, so a short tag fills its touch area instead of floating centered in it
            Modifier
                .widthIn(min = 48.dp)
                .border(BorderStroke(1.dp, border), RoundedCornerShape(8.dp))
                .background(container, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            when (mark) {
                TagMark.INCLUDE -> Icon(Icons.Filled.Check, null, tint = content, modifier = Modifier.size(16.dp))
                TagMark.BLOCK -> Icon(Icons.Filled.Block, null, tint = content, modifier = Modifier.size(16.dp))
                TagMark.NONE -> Unit
            }
            Text(
                tag,
                color = content,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (mark == TagMark.BLOCK) TextDecoration.LineThrough else null,
            )
        }
    }
}
