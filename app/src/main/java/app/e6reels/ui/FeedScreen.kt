package app.e6reels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.e6reels.MainViewModel
import app.e6reels.data.Post

@Composable
fun FeedScreen(
    vm: MainViewModel,
    pagerState: PagerState,
    active: Boolean,
    landscape: Boolean,
    onToggleLandscape: () -> Unit,
    onControlsVisible: (Boolean) -> Unit,
    bottomReserved: Dp,
    onOpenDetails: (Post) -> Unit,
    onOpenLists: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val liked by vm.likedIds.collectAsStateWithLifecycle()
    val activeList by vm.activeList.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (feed.posts.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(bottom = bottomReserved).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    feed.error != null -> {
                        Text(feed.error.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Button(onClick = vm::loadMore) { Text("Try again") }
                    }
                    feed.loading || !feed.endReached -> CircularProgressIndicator(color = Accent)
                    else -> {
                        Text("Nothing found", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        OutlinedButton(onClick = onOpenLists) { Text("Lists") }
                    }
                }
            }
        } else {
            ReelsPager(
                posts = feed.posts,
                pagerState = pagerState,
                active = active,
                likedIds = liked,
                onToggleLike = vm::toggleLike,
                onLike = vm::like,
                onOpenDetails = onOpenDetails,
                onNearEnd = vm::loadMore,
                landscape = landscape,
                onToggleLandscape = onToggleLandscape,
                onControlsVisible = onControlsVisible,
                bottomReserved = bottomReserved,
                endReached = feed.endReached,
                onRefresh = vm::refreshFeed,
                onShown = vm::markSeen,
                onDislike = vm::dislike,
            )
        }

        if (!landscape) {
        // top bar: active list (left) + global filters (right), kept tight against the top edge
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent)))
                // fixed, not statusBarsPadding(): the status bar is hidden here but reappears behind sheets,
                // and the row must not jump when it does
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onOpenLists)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    activeList?.name ?: "Everything",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            if (feed.loading && feed.posts.isNotEmpty()) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            }
            IconButton(onClick = onOpenSearch, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
            }
            IconButton(onClick = onOpenFilters, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Tune, contentDescription = "Filters", tint = Color.White)
            }
        }
        }
    }
}
