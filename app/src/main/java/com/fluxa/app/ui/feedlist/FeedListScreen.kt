package com.fluxa.app.ui.feedlist

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.DismissDirection
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.UiState
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListRoute(
    onOpenArticle: (String) -> Unit,
    viewModel: FeedListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Fluxa") },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(innerPadding)
        ) {
            AnimatedContent(uiState, label = "feed-list-state") { state ->
                when (state) {
                    UiState.Loading -> LoadingState()
                    UiState.Empty -> MessageState("暂无文章")
                    is UiState.Error -> MessageState(state.message)
                    is UiState.Success -> FeedListScreen(
                        articles = state.data,
                        onArticleClick = {
                            viewModel.markRead(it)
                            onOpenArticle(it)
                        },
                        onMarkRead = viewModel::markRead,
                        onToggleStar = viewModel::toggleStar,
                        onReachEnd = viewModel::loadMore
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedListScreen(
    articles: List<Article>,
    onArticleClick: (String) -> Unit,
    onMarkRead: (String) -> Unit,
    onToggleStar: (String) -> Unit,
    onReachEnd: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(items = articles, key = { _, item -> item.id }) { index, article ->
            if (index >= articles.lastIndex - 2) onReachEnd()

            val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                positionalThreshold = { total -> total * 0.35f },
                confirmValueChange = { value ->
                    when (value) {
                        SwipeToDismissBoxValue.StartToEnd -> onToggleStar(article.id)
                        SwipeToDismissBoxValue.EndToStart -> onMarkRead(article.id)
                        SwipeToDismissBoxValue.Settled -> Unit
                    }
                    true
                }
            )

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    val direction = dismissState.dismissDirection
                    val isStarAction = direction == DismissDirection.StartToEnd
                    val color = if (isStarAction) Color(0xFF204C30) else Color(0xFF2D3A55)
                    val alignment = if (isStarAction) Alignment.CenterStart else Alignment.CenterEnd
                    val icon = if (isStarAction) Icons.Outlined.Star else Icons.Outlined.Done

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color)
                            .padding(horizontal = 20.dp),
                        contentAlignment = alignment
                    ) {
                        Icon(icon, contentDescription = null, tint = Color.White)
                    }
                }
            ) {
                ArticleCard(article = article, onClick = { onArticleClick(article.id) })
            }
        }
    }
}

@Composable
private fun ArticleCard(article: Article, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${article.feedName} · ${relativeTime(article.publishedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (!article.isRead || article.isStarred) {
                Text(
                    text = buildString {
                        if (!article.isRead) append("未读")
                        if (article.isStarred) {
                            if (isNotEmpty()) append(" · ")
                            append("已收藏")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message)
    }
}

private fun relativeTime(time: Instant): String {
    val duration = Duration.between(time, Instant.now())
    return when {
        duration.toMinutes() < 1 -> "刚刚"
        duration.toHours() < 1 -> "${duration.toMinutes()} 分钟前"
        duration.toDays() < 1 -> "${duration.toHours()} 小时前"
        else -> "${duration.toDays()} 天前"
    }
}
