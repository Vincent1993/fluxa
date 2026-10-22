package com.fluxa.app.ui

import com.fluxa.app.ui.feedlist.*
import org.junit.Assert.*
import org.junit.Test

class SwipeChoiceTest {
    @Test fun selectedSourceOverridesOnlyItselfAndUnsetInheritsGlobal() {
        val global = SwipeChoice(left = SwipeAction.ToggleRead, deepLeft = SwipeAction.ReadLater)
        val selected = SwipeChoice(left = SwipeAction.Star, deepLeft = SwipeAction.Read)
        val config = SwipeConfig(global, mapOf("direct-rss:a" to selected))
        assertEquals(selected, config.choice("direct-rss:a"))
        assertEquals(global, config.choice("direct-rss:b"))
        assertEquals(SwipeAction.Star, config.choice("remote").deepLeft)
        assertEquals(SwipeAction.Star, config.choice("direct-rss:saved-pages").deepLeft)
        assertEquals(SwipeAction.ReadLater, global.action(-1, true))
        assertEquals(SwipeAction.ToggleRead, global.action(-1, false))
        try { SwipeChoice(left = SwipeAction.Read, deepLeft = SwipeAction.ToggleRead); fail() } catch (_: IllegalArgumentException) { }
    }
}
