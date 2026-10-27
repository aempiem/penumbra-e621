package app.e6reels.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.saveable.rememberSaveable
import app.e6reels.data.OfflineLimit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.e6reels.MainViewModel
import app.e6reels.data.Post
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LikesScreen(vm: MainViewModel, onOpen: (List<Post>, Int) -> Unit) {
    val likes by vm.likes.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val posts = if (showHistory) history else likes

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)) {
        Segmented(
            listOf("Likes · ${likes.size}", "History"),
            if (showHistory) 1 else 0,
            { showHistory = it == 1 },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (posts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (showHistory) Icons.Outlined.History else Icons.Filled.FavoriteBorder,
                    null,
                    tint = Color(0x55FFFFFF),
                    modifier = Modifier.size(56.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(posts, key = { it.id }) { post ->
                    Box(
                        Modifier
                            .aspectRatio(0.75f)
                            .clickable { onOpen(posts, posts.indexOf(post)) },
                    ) {
                        AsyncImage(
                            model = post.thumbUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (post.isVideo) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen viewer over a snapshot of the liked list, opened from the grid. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LikesViewer(
    posts: List<Post>,
    startIndex: Int,
    vm: MainViewModel,
    landscape: Boolean,
    onToggleLandscape: () -> Unit,
    onOpenDetails: (Post) -> Unit,
    onClose: () -> Unit,
) {
    val liked by vm.likedIds.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = startIndex) { posts.size }
    BackHandler { if (landscape) onToggleLandscape() else onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ReelsPager(
            posts = posts,
            pagerState = pagerState,
            active = true,
            likedIds = liked,
            onToggleLike = vm::toggleLike,
            onLike = vm::like,
            onOpenDetails = onOpenDetails,
            onNearEnd = {},
            landscape = landscape,
            onToggleLandscape = onToggleLandscape,
        )
        if (!landscape) {
            IconButton(onClick = onClose, modifier = Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(vm: MainViewModel) {
    val name by vm.profileName.collectAsStateWithLifecycle()
    val likes by vm.likes.collectAsStateWithLifecycle()
    val topTags by vm.topTags.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val offlineLimit by vm.offlineLimit.collectAsStateWithLifecycle()
    val offlineUsage by vm.offlineUsage.collectAsStateWithLifecycle()
    val seenCount by vm.seenCount.collectAsStateWithLifecycle()
    val blockedTags by vm.blockedTags.collectAsStateWithLifecycle()

    var editName by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.first().uppercase(), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(
                onClick = { editName = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            ) { Text("Edit") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("${likes.size}", if (likes.size == 1) "like" else "likes")
            Stat("${lists.size}", if (lists.size == 1) "list" else "lists")
        }

        // One scrolling row per group, so a long list of tags never pushes the settings down the screen.
        if (topTags.isNotEmpty()) {
            Text("Your tastes", color = Color(0xAAFFFFFF), style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(topTags, key = { it }) { tag ->
                    SuggestionChip(onClick = { vm.addTag(tag) }, label = { Text(tag) })
                }
            }
        }

        Text(
            if (blockedTags.isEmpty()) "Blocked tags" else "Blocked tags · ${blockedTags.size}",
            color = Color(0xAAFFFFFF),
            style = MaterialTheme.typography.bodySmall,
        )
        if (blockedTags.isEmpty()) {
            Text("None", color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(blockedTags, key = { it }) { tag ->
                    InputChip(
                        selected = false,
                        onClick = { vm.unblock(tag) },
                        label = { Text(tag, color = MaterialTheme.colorScheme.error) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Unblock $tag", modifier = Modifier.size(InputChipDefaults.IconSize)) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("penumbra-backup.json") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Export")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text("Import")
            }
        }
        Text("Keep likes on this phone", color = Color(0xAAFFFFFF), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Segmented(OfflineLimit.entries.map { it.label }, OfflineLimit.entries.indexOf(offlineLimit), { vm.setOfflineLimit(OfflineLimit.entries[it]) })
        Text(
            "${offlineUsage / (1024 * 1024)} MB used",
            color = Color(0x99FFFFFF),
            style = MaterialTheme.typography.labelMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(
                onClick = vm::clearHistory,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("Clear history") }
            OutlinedButton(
                onClick = vm::forgetSeen,
                enabled = seenCount > 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("Forget seen ($seenCount)") }
        }

        TextButton(
            onClick = { confirmClear = true },
            enabled = likes.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
        ) {
            Text("Delete all likes", color = MaterialTheme.colorScheme.error)
        }
    }

    if (editName) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("Profile name") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(24) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setName(text)
                    editName = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = { editName = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text("Cancel") }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete ${likes.size} likes?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearLikes()
                    confirmClear = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(label, color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelMedium)
    }
}
