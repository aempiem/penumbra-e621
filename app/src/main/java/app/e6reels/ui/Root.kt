package app.e6reels.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.e6reels.MainViewModel
import app.e6reels.data.FOR_YOU_ID
import app.e6reels.data.Post
import app.e6reels.data.SEARCH_ID
import app.e6reels.data.TagMark
import app.e6reels.data.TagList

@Composable
fun Root(vm: MainViewModel) {
    val adultOk by vm.adultOk.collectAsStateWithLifecycle()
    when (adultOk) {
        null -> Box(Modifier.fillMaxSize().background(Color.Black))
        false -> AgeGate(onConfirm = vm::confirmAdult)
        true -> MainContent(vm)
    }
}

@Composable
private fun AgeGate(onConfirm: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Penumbra", color = Color.White, style = MaterialTheme.typography.headlineLarge)
        Text("Adult content (18+)", color = Color(0xCCFFFFFF), textAlign = TextAlign.Center)
        Button(onClick = onConfirm) { Text("I am 18 or older") }
    }
}

@Composable
private fun MainContent(vm: MainViewModel) {
    val context = LocalContext.current
    val feed by vm.feed.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val topTags by vm.topTags.collectAsStateWithLifecycle()
    val forYouAdjust by vm.forYouAdjust.collectAsStateWithLifecycle()
    val forYouNow by vm.forYouNow.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // which bottom sheet is open: none / lists / list editor / filters
    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    var viewer by remember { mutableStateOf<Pair<List<Post>, Int>?>(null) }
    var details by remember { mutableStateOf<Post?>(null) }
    val activeList by vm.activeList.collectAsStateWithLifecycle()
    // the video controls take the bottom edge, so the navigation bar steps aside while they are visible
    var videoControlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { videoControlsVisible = false }
    var barHeightPx by remember { mutableIntStateOf(0) }
    val navHide by animateFloatAsState(
        targetValue = if (videoControlsVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "navHide",
    )

    // landscape "theater" mode: rotated, swipes locked, system bars hidden, until toggled off
    var landscape by rememberSaveable { mutableStateOf(false) }
    val reserved = if (landscape) 0.dp else with(LocalDensity.current) { barHeightPx.toDp() }
    val view = LocalView.current
    // status bar hidden on every screen, so switching tabs never shows it or shifts the layout;
    // a swipe from the top edge brings it back briefly
    LaunchedEffect(landscape) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        view.context.findActivity()?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val bars = WindowCompat.getInsetsController(window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        when {
            landscape -> bars.hide(WindowInsetsCompat.Type.systemBars())
            else -> {
                bars.show(WindowInsetsCompat.Type.navigationBars())
                bars.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    BackHandler(enabled = landscape && viewer == null) { landscape = false }

    // hoisted so the feed keeps its position while the user visits other tabs
    // The count is read here, in composition, so it is always the size of the very list passed to the pager.
    // (A lambda reading `feed` live gets ahead of the list by a frame when more posts load.)
    val postCount = feed.posts.size
    val feedPager = rememberPagerState { postCount }
    LaunchedEffect(feed.generation) { feedPager.scrollToPage(0) }

    // transient feedback as a snackbar; messages that can be undone carry a "Undo" action
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message = m.text,
                    actionLabel = if (m.undo != null) "Undo" else null,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) m.undo?.invoke()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Black,
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(data, containerColor = Surface1, contentColor = Color.White, actionColor = Accent)
                }
            },
            bottomBar = {
                // The bar keeps its space (so the video area never resizes) and just slides away;
                // the video controls drop into the room it leaves, in sync with it.
                if (!landscape) NavigationBar(
                    containerColor = Color.Black,
                    modifier = Modifier
                        .onSizeChanged { barHeightPx = it.height }
                        .graphicsLayer {
                            translationY = size.height * navHide
                            alpha = 1f - navHide
                        },
                ) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        indicatorColor = Color(0xFF2A1219),
                        unselectedIconColor = Color(0x99FFFFFF),
                        unselectedTextColor = Color(0x99FFFFFF),
                    )
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.PlayCircle, "Feed") }, colors = colors)
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Favorite, "Likes") }, colors = colors)
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Person, "Profile") }, colors = colors)
                }
            },
        ) { inner ->
            // The feed fills the screen under the navigation bar (it reserves the bar's height itself),
            // so the bar can slide away without the video area resizing.
            Box(if (tab == 0) Modifier.padding(top = inner.calculateTopPadding()) else Modifier.padding(inner)) {
                when (tab) {
                    0 -> FeedScreen(
                        vm, feedPager, active = viewer == null,
                        landscape = landscape,
                        onToggleLandscape = { landscape = !landscape },
                        onControlsVisible = { videoControlsVisible = it },
                        bottomReserved = reserved,
                        onOpenDetails = { details = it },
                        onOpenLists = { sheet = Sheet.Lists },
                        onOpenSearch = { sheet = Sheet.Search },
                        onOpenFilters = { sheet = Sheet.Filters },
                    )
                    1 -> LikesScreen(vm, onOpen = { list, i -> viewer = list to i })
                    else -> ProfileScreen(vm)
                }
            }
        }

        viewer?.let { (list, start) ->
            LikesViewer(
                list, start, vm,
                landscape = landscape,
                onToggleLandscape = { landscape = !landscape },
                onOpenDetails = { details = it },
                onClose = { viewer = null },
            )
        }
    }

    details?.let { post ->
        // "For you" keeps its own pinned/blocked tags; every other list marks tags directly
        val source = if (activeList?.id == FOR_YOU_ID) forYouAdjust else activeList
        PostDetailsSheet(
            post = post,
            markOf = { tag -> source?.markOf(tag) ?: TagMark.NONE },
            onApply = vm::applyTagMarks,
            onSave = { vm.saveToPhone(post) },
            onDismiss = { details = null },
        )
    }

    when (val current = sheet) {
        Sheet.None -> Unit
        Sheet.Lists -> ListsSheet(
            lists = lists,
            activeId = settings.activeListId,
            forYouPreview = if (forYouNow.isNotEmpty()) "now: " + forYouNow.joinToString(" · ") else topTags.take(3).joinToString(" · "),
            onSelect = {
                vm.selectList(it)
                sheet = Sheet.None
            },
            onEditForYou = { sheet = Sheet.Editor(forYouAdjust) },
            onEdit = { sheet = Sheet.Editor(it) },
            onDismiss = { sheet = Sheet.None },
        )
        is Sheet.Editor -> ListEditor(
            initial = current.list,
            suggest = vm::suggest,
            onSave = {
                vm.saveList(it)
                sheet = Sheet.None
            },
            onDelete = {
                vm.deleteList(it)
                sheet = Sheet.Lists
            },
            onDismiss = { sheet = Sheet.Lists },
        )
        Sheet.Search -> SearchSheet(
            current = if (settings.activeListId == SEARCH_ID) settings.searchTags else emptyList(),
            suggest = vm::suggest,
            onSearch = vm::search,
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Filters -> FilterSheet(
            initial = settings,
            onApply = vm::applySettings,
            onDismiss = { sheet = Sheet.None },
        )
    }
}

private sealed interface Sheet {
    data object None : Sheet
    data object Lists : Sheet
    data class Editor(val list: TagList?) : Sheet
    data object Filters : Sheet
    data object Search : Sheet
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

