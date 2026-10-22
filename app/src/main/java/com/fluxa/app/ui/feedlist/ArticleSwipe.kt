package com.fluxa.app.ui.feedlist

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class RowAnchor { Left, Neutral, Right }

private data class SwipePresentation(val direction: Int, val stage: Int, val invalid: Boolean)

/** Observe the producer synchronously: a collector can see Stop after a new DOWN/reset. */
private class ReleaseInteractions(private val observe: (Interaction) -> Unit) : MutableInteractionSource {
    private val delegate = MutableInteractionSource()
    override val interactions = delegate.interactions
    override suspend fun emit(interaction: Interaction) { observe(interaction); delegate.emit(interaction) }
    override fun tryEmit(interaction: Interaction): Boolean { observe(interaction); return delegate.tryEmit(interaction) }
}

/** Foundation owns movement/slop/axis arbitration. This object owns only release policy. */
private class InputEligibility(invalid: Boolean = false) {
    var invalid by mutableStateOf(invalid)
}

private class RowStream {
    var start: DragInteraction.Start? = null
    var input = InputEligibility()
    var activeInput: InputEligibility? by mutableStateOf(null)
    var direction = 0f
    var choice: SwipeChoice? = null
    var disposed = false
    var reset: Job? = null
}

@Composable
internal fun ArticleSwipe(read: Boolean, starred: Boolean, onRead: () -> Unit, onStar: () -> Unit,
    articleId: String = "", onOpen: (() -> Unit)? = null, onActions: (() -> Unit)? = null,
    choice: SwipeChoice = SwipeChoice(), onAction: ((SwipeAction) -> Unit)? = null, readLater: Boolean = false,
    content: @Composable () -> Unit) = key(articleId) {
    val latestRead by rememberUpdatedState(onRead)
    val latestStar by rememberUpdatedState(onStar)
    val alreadyRead by rememberUpdatedState(read)
    val latestChoice by rememberUpdatedState(choice)
    val latestAction by rememberUpdatedState(onAction)
    val density = LocalDensity.current
    val edge = with(density) { 24.dp.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    val leftEdge = maxOf(edge, WindowInsets.systemGestures.getLeft(density, layoutDirection).toFloat())
    val rightEdge = maxOf(edge, WindowInsets.systemGestures.getRight(density, layoutDirection).toFloat())
    var width by remember { mutableIntStateOf(0) }
    val deep = minOf(with(density) { 176.dp.toPx() }, (width - leftEdge - rightEdge).coerceAtLeast(1f) * .5f)
    val shallow = deep / 2f
    val currentDeep by rememberUpdatedState(deep)
    val state = remember { AnchoredDraggableState(RowAnchor.Neutral) }
    SideEffect {
        state.updateAnchors(DraggableAnchors {
            RowAnchor.Left at -maxOf(width.toFloat(), 1f)
            RowAnchor.Neutral at 0f
            RowAnchor.Right at maxOf(width.toFloat(), 1f)
        })
    }
    val scope = rememberCoroutineScope()
    val stream = remember { RowStream() }
    val interactions = remember {
        ReleaseInteractions { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    stream.reset?.cancel()
                    state.dispatchRawDelta(-state.offset.takeUnless { it.isNaN() }.orZero())
                    stream.direction = 0f
                    stream.choice = latestChoice
                    stream.activeInput = stream.input
                    stream.start = interaction
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    val start = when (interaction) {
                        is DragInteraction.Stop -> interaction.start
                        is DragInteraction.Cancel -> interaction.start
                        else -> null
                    }
                    if (start === stream.start && start != null) {
                        // Snapshot before callback/reset; only this exact Stop can commit.
                        val final = state.offset
                        stream.start = null
                        if (interaction is DragInteraction.Stop && stream.activeInput?.invalid == false && !stream.disposed && stream.choice == latestChoice &&
                            abs(final) >= currentDeep / 2f) {
                            val selected = latestChoice.action(if (final < 0) -1 else 1, abs(final) >= currentDeep)
                            val action = latestAction
                            if (action != null) { if (selected != SwipeAction.Read || !alreadyRead) action(selected) }
                            else if (selected in SwipeChoice.readActions) { if (!alreadyRead) latestRead() } else latestStar()
                        }
                        stream.reset = scope.launch { state.animateTo(RowAnchor.Neutral, tween(180)) }
                    }
                }
            }
        }
    }
    DisposableEffect(stream) {
        onDispose { stream.disposed = true; stream.start = null; stream.reset?.cancel() }
    }
    val drag = rememberDraggableState { delta ->
        if (!stream.disposed) {
            val before = state.offset.takeUnless { it.isNaN() }.orZero()
            val after = before + delta
            if (stream.direction == 0f && after != 0f) stream.direction = if (after > 0) 1f else -1f
            if (after * stream.direction < 0f) stream.activeInput?.invalid = true
            state.dispatchRawDelta(delta)
        }
    }
    // Only discrete presentation changes recompose the row; translation reads pixels in the layer.
    val presentation by remember(state, stream, shallow, deep) {
        derivedStateOf {
            val offset = state.offset
            val direction = when {
                !offset.isFinite() || offset == 0f -> 0
                offset < 0f -> -1
                else -> 1
            }
            SwipePresentation(direction, when {
                direction == 0 -> 0
                abs(offset) >= deep -> 2
                abs(offset) >= shallow -> 1
                else -> 0
            }, stream.activeInput?.invalid == true)
        }
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.clipToBounds()
        .testTag("article-swipe").semantics(mergeDescendants = true) {
            customActions = buildList {
                if (!read) add(CustomAccessibilityAction("标记已读") { onRead(); true })
                onActions?.let { action -> add(CustomAccessibilityAction("文章操作") { action(); true }) }
                add(CustomAccessibilityAction(if (starred) "取消收藏" else "收藏文章") { onStar(); true })
                if (onAction != null) {
                    add(CustomAccessibilityAction(if (read) "标记未读" else "切换为已读") { onAction(SwipeAction.ToggleRead); true })
                    if (choice.deepLeft == SwipeAction.ReadLater || choice.deepRight == SwipeAction.ReadLater)
                        add(CustomAccessibilityAction(if (readLater) "移除稍后读" else "加入稍后读") { onAction(SwipeAction.ReadLater); true })
                }
            }
        }.pointerInput(leftEdge, rightEdge) {
            // Non-consuming safety observer, except terminal click suppression. No drag engine.
            awaitEachGesture {
                val down = awaitFirstDown(false, PointerEventPass.Initial)
                val eligibility = InputEligibility(down.position.x < leftEdge || down.position.x > size.width - rightEdge)
                stream.input = eligibility
                var moved = false
                var parentOwns = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.size != 1 || change == null) eligibility.invalid = true
                    if (change != null) {
                        val delta = change.position - down.position
                        if (delta.getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            if (abs(delta.y) * 1.5f > abs(delta.x)) eligibility.invalid = true
                        }
                        if (!change.pressed && change.isConsumed) eligibility.invalid = true
                        // Do not consume a successful draggable UP (that would turn it into Cancel).
                        if (!change.pressed && moved && eligibility.invalid && !parentOwns && stream.start == null) change.consume()
                    }
                    if (event.changes.none { it.pressed }) break
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    if (stream.start == null && final.changes.any { it.isConsumed && it.position != it.previousPosition }) parentOwns = true
                } while (true)
            }
        }.draggable(drag, Orientation.Horizontal, interactionSource = interactions,
            startDragImmediately = false)
        .then(if (onOpen != null && onActions != null) Modifier.combinedClickable(onClickLabel = "阅读文章", onClick = onOpen,
            onLongClickLabel = "文章操作", onLongClick = onActions)
        else if (onOpen != null) Modifier.clickable(onClickLabel = "阅读文章", onClick = onOpen) else Modifier)) {
        if (presentation.direction != 0) Surface(Modifier.matchParentSize(),
            color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            val selected = choice.action(presentation.direction, presentation.stage == 2)
            val markRead = selected == SwipeAction.Read
            val action = when (selected) {
                SwipeAction.Read -> if (read) "已读 / 无需操作" else "标记已读"
                SwipeAction.ToggleRead -> if (read) "标记未读" else "标记已读"
                SwipeAction.Star -> if (starred) "取消收藏" else "收藏"
                SwipeAction.ReadLater -> if (readLater) "移除稍后读" else "加入稍后读"
            }
            val stage = if (presentation.stage == 2) "第二段" else "第一段"
            val description = when {
                presentation.invalid -> "已取消 · 抬手重试"
                presentation.stage == 0 -> "未达到阈值，松手取消"
                markRead && read -> "$stage·已读·无需操作"
                else -> "$stage·$action·松手执行，拖回取消"
            }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(if (presentation.direction < 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .width(64.dp)
                    .clipToBounds().testTag("article-swipe-preview").semantics {
                        contentDescription = action
                        stateDescription = description
                    }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(when (selected) {
                        SwipeAction.Read, SwipeAction.ToggleRead -> Icons.Outlined.Check
                        SwipeAction.ReadLater -> Icons.Outlined.BookmarkBorder
                        SwipeAction.Star -> if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder
                    },
                        null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.inverseOnSurface.copy(
                            alpha = if ((markRead && read) || presentation.invalid) .35f else 1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { index ->
                            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.inverseOnSurface.copy(
                                alpha = if (presentation.stage > index) 1f else .25f), CircleShape))
                        }
                    }
                }
            }
        }
        Box(Modifier.graphicsLayer { translationX = state.offset.takeIf { it.isFinite() } ?: 0f }
            .background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

private fun Float?.orZero() = this ?: 0f
