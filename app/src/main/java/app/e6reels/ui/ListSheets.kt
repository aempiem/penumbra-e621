package app.e6reels.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.e6reels.data.FOR_YOU_ID
import app.e6reels.data.TagList
import app.e6reels.data.TagSuggestion
import app.e6reels.data.normalizeTag
import kotlinx.coroutines.delay

private fun categoryColor(category: Int) = when (category) {
    1 -> Color(0xFFF2AC08) // artist
    3 -> Color(0xFFDD00DD) // copyright
    4 -> Color(0xFF00AA00) // character
    5 -> Color(0xFFED5D1F) // species
    else -> Color(0xFF6CB4FF) // general
}

private fun compactCount(n: Int) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
}

private fun TagList.summary(): String = when {
    include.isNotEmpty() -> include.take(3).joinToString(" · ") + if (include.size > 3) " +${include.size - 3}" else ""
    blocked.isNotEmpty() -> "blocks only"
    else -> "no tags"
}

/** Picks the list the feed is built from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsSheet(
    lists: List<TagList>,
    activeId: Long,
    forYouPreview: String,
    onSelect: (Long) -> Unit,
    onEditForYou: () -> Unit,
    onEdit: (TagList?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
    ) {
        HideStatusBarInSheet()
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Lists", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item {
                    ListRow(
                        "For you",
                        forYouPreview.ifBlank { "no likes yet" },
                        activeId == FOR_YOU_ID,
                        { onSelect(FOR_YOU_ID) },
                        onEditForYou,
                    )
                }
                item { ListRow("Everything", "the whole site", activeId == 0L, { onSelect(0) }, null) }
                items(lists, key = { it.id }) { l ->
                    ListRow(l.name, l.summary(), l.id == activeId, { onSelect(l.id) }, { onEdit(l) })
                }
            }
            Button(
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Text("  New list")
            }
        }
    }
}

@Composable
private fun ListRow(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit, onEdit: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = if (selected) "Active" else null,
            tint = if (selected) Accent else Color.Transparent,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit", tint = Color(0xAAFFFFFF)) }
        }
    }
}

/** Create or edit a list: name, any/all mode and its tags. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListEditor(
    initial: TagList?,
    suggest: suspend (String) -> List<TagSuggestion>,
    onSave: (TagList) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val isForYou = initial?.id == FOR_YOU_ID
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var any by remember { mutableStateOf(initial?.anyMode ?: true) }
    var include by remember { mutableStateOf(initial?.include ?: emptyList()) }
    var blocked by remember { mutableStateOf(initial?.blocked ?: emptyList()) }
    var input by remember { mutableStateOf("") }
    var blockMode by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<TagSuggestion>()) }

    LaunchedEffect(input) {
        val q = normalizeTag(input)?.first.orEmpty()
        if (q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = suggest(q).take(6)
    }

    fun addTag(raw: String) {
        val (tag, negative) = normalizeTag(raw) ?: return
        val next = TagList(name = "", include = include, blocked = blocked).withTag(tag, blockMode || negative)
        include = next.include
        blocked = next.blocked
        input = ""
        suggestions = emptyList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
    ) {
        HideStatusBarInSheet()
        Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        isForYou -> "For you settings"
                        initial == null -> "New list"
                        else -> "Edit list"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                if (initial != null && !isForYou) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "Delete list", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!isForYou) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(30) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("List name") },
                    )
                    Segmented(listOf("Any of them", "All together"), if (any) 0 else 1, { any = it == 0 })
                    HorizontalDivider(color = Color(0x22FFFFFF))
                }

                Segmented(listOf(if (isForYou) "Always include" else "Include", "Block"), if (blockMode) 1 else 0, { blockMode = it == 1 })
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search tag") },
                    trailingIcon = {
                        IconButton(onClick = { addTag(input) }, enabled = input.isNotBlank()) {
                            Icon(Icons.Filled.Add, contentDescription = "Add tag")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag(input) }),
                )
                suggestions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { addTag(s.name) }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (s.antecedent != null) "${s.antecedent} → ${s.name}" else s.name,
                            color = categoryColor(s.category),
                            modifier = Modifier.weight(1f),
                        )
                        Text(compactCount(s.postCount), color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (include.isNotEmpty() || blocked.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        include.forEach { tag -> TagChip(tag) { include = include - tag } }
                        blocked.forEach { tag -> TagChip("−$tag") { blocked = blocked - tag } }
                    }
                }
            }

            if (confirmDelete && initial != null) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete \"${initial.name}\"?") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            onDelete(initial.id)
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { confirmDelete = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) { Text("Cancel") }
                    },
                )
            }

            Button(
                onClick = {
                    val finalName = name.trim().ifBlank { include.firstOrNull() ?: "New list" }
                    onSave((initial ?: TagList(name = "")).copy(name = if (isForYou) initial!!.name else finalName, include = include, blocked = blocked, anyMode = any))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = {
            Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(InputChipDefaults.IconSize))
        },
    )
}

/** A one-off search: type tags (space separated, "-" to exclude) and the feed shows them right away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    current: List<String>,
    suggest: suspend (String) -> List<TagSuggestion>,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(current.joinToString(" ").let { if (it.isEmpty()) it else "$it " }) }
    var suggestions by remember { mutableStateOf(emptyList<TagSuggestion>()) }

    // suggest for the word being typed (the last one)
    LaunchedEffect(input) {
        val last = input.substringAfterLast(' ')
        val q = normalizeTag(last)?.first.orEmpty()
        if (q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = suggest(q).take(6)
    }

    fun complete(tag: String) {
        val head = input.substringBeforeLast(' ', "")
        val negative = input.substringAfterLast(' ').startsWith("-")
        input = (if (head.isBlank()) "" else "$head ") + (if (negative) "-" else "") + tag + " "
        suggestions = emptyList()
    }

    fun go() {
        if (input.isBlank()) return
        onSearch(input)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
    ) {
        HideStatusBarInSheet()
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Text("Search", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("wolf  -gore") },
                trailingIcon = {
                    IconButton(onClick = ::go, enabled = input.isNotBlank()) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
            )
            suggestions.forEach { sg ->
                Row(
                    Modifier.fillMaxWidth().clickable { complete(sg.name) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (sg.antecedent != null) "${sg.antecedent} → ${sg.name}" else sg.name,
                        color = categoryColor(sg.category),
                        modifier = Modifier.weight(1f),
                    )
                    Text(compactCount(sg.postCount), color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelMedium)
                }
            }
            Button(onClick = ::go, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Search") }
        }
    }
}
