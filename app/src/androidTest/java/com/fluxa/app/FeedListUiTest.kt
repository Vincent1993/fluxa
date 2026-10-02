package com.fluxa.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.feedlist.*
import com.fluxa.app.ui.theme.FluxaTheme
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class FeedListUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cacheRemainsReadableDuringOfflineErrorAndActionsAreAccessible() {
        var opened: String? = null
        var starred: String? = null
        val article = Article("tag:google.com,2005:reader/item/123", "离线缓存正文", "我的订阅",
            Instant.EPOCH, false, false, "<p>Cached</p>")
        compose.setContent {
            FluxaTheme {
                FeedListScreen(FeedListState(articles = listOf(article), message = "离线，可阅读缓存"),
                    { opened = it }, {}, {}, {}, {}, {}, {}, {}, { starred = it }, {}, {})
            }
        }
        compose.onNodeWithText("离线缓存正文").assertIsDisplayed()
        compose.onNodeWithText("离线，可阅读缓存").assertIsDisplayed()
        compose.onNodeWithText("收藏文章").performClick()
        assertEquals(article.id, starred)
        compose.onNodeWithText("离线缓存正文").performClick()
        assertEquals(article.id, opened)
    }

    @Test fun emptyCacheOffersRefreshSubscriptionAndLogin() {
        compose.setContent {
            FluxaTheme {
                FeedListScreen(FeedListState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("刷新").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithText("添加 RSS 订阅").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("登录 NewsBlur").assertIsDisplayed()
    }
}
