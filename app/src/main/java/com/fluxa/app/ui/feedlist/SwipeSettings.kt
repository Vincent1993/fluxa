package com.fluxa.app.ui.feedlist

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxa.app.ui.components.IconAction
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class SwipeAction(val label: String) { Read("标记已读"), ToggleRead("已读 / 未读"), Star("收藏 / 取消收藏"), ReadLater("稍后读 / 移除") }
data class SwipeChoice(val left: SwipeAction = SwipeAction.Read, val right: SwipeAction = SwipeAction.Star,
    val deepLeft: SwipeAction = SwipeAction.Star, val deepRight: SwipeAction = SwipeAction.Read) {
    init {
        require(left != SwipeAction.ReadLater && right != SwipeAction.ReadLater)
        require(deepLeft != left && deepRight != right)
        require(!(left in readActions && deepLeft in readActions) && !(right in readActions && deepRight in readActions))
    }
    fun action(direction: Int, deep: Boolean): SwipeAction = if (direction < 0) { if (deep) deepLeft else left } else { if (deep) deepRight else right }
    fun forLocal(local: Boolean): SwipeChoice = if (local) this else copy(
        deepLeft = if (deepLeft == SwipeAction.ReadLater) { if (left in readActions) SwipeAction.Star else SwipeAction.Read } else deepLeft,
        deepRight = if (deepRight == SwipeAction.ReadLater) { if (right in readActions) SwipeAction.Star else SwipeAction.Read } else deepRight)
    companion object { val readActions = setOf(SwipeAction.Read, SwipeAction.ToggleRead) }
}
data class SwipeConfig(val global: SwipeChoice = SwipeChoice(), val sources: Map<String, SwipeChoice> = emptyMap()) {
    fun choice(source: String) = (sources[source] ?: global).forLocal(source.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX) && source != com.fluxa.app.data.directrss.SAVED_PAGE_SOURCE)
}
@Singleton
class SwipeSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("rss_swipes_v1", Context.MODE_PRIVATE)
    @Synchronized fun load(): SwipeConfig = runCatching {
        val o = JSONObject(prefs.getString("choices", "{}")!!)
        fun decode(j: JSONObject) = SwipeChoice(SwipeAction.valueOf(j.getString("left")), SwipeAction.valueOf(j.getString("right")),
            SwipeAction.valueOf(j.getString("deepLeft")), SwipeAction.valueOf(j.getString("deepRight")))
        val sources = o.optJSONObject("sources") ?: JSONObject()
        SwipeConfig(o.optJSONObject("global")?.let(::decode) ?: SwipeChoice(), sources.keys().asSequence().take(500).associateWith { decode(sources.getJSONObject(it)) })
    }.getOrDefault(SwipeConfig())
    @Synchronized fun mergeBackup(incoming: SwipeConfig) {
        val old = load()
        save(if (prefs.contains("choices")) old.copy(sources = incoming.sources + old.sources) else incoming)
    }
    @Synchronized fun save(config: SwipeConfig) {
        require(config.sources.size <= 500)
        fun encode(c: SwipeChoice) = JSONObject().put("left", c.left.name).put("right", c.right.name).put("deepLeft", c.deepLeft.name).put("deepRight", c.deepRight.name)
        check(prefs.edit().putString("choices", JSONObject().put("global", encode(config.global)).put("sources", JSONObject().apply {
            config.sources.forEach { (id, c) -> put(id, encode(c)) }
        }).toString()).commit())
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeSettingsSheet(config: SwipeConfig, source: String?, sourceTitle: String?, save: (SwipeConfig) -> Unit, close: () -> Unit) {
    var selectedSource by remember { mutableStateOf(false) }
    val key = if (selectedSource) source else null
    val localChoice = key == null || (key.startsWith(com.fluxa.app.data.directrss.LOCAL_PREFIX) && key != com.fluxa.app.data.directrss.SAVED_PAGE_SOURCE)
    val choice = (key?.let { config.sources[it] } ?: config.global).forLocal(localChoice)
    fun update(value: SwipeChoice) { save(if (key == null) config.copy(global = value) else config.copy(sources = config.sources + (key to value))) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("列表滑动", style = MaterialTheme.typography.headlineSmall)
            Row { Text(if (selectedSource) sourceTitle ?: "当前订阅" else "所有订阅", Modifier.weight(1f))
                if (source != null) IconAction("切换全局或当前订阅", Icons.Outlined.Tune, { selectedSource = !selectedSource }) }
            if (key != null) {
                Text(if (key in config.sources) "此订阅单独设置" else "此订阅跟随全局")
                IconAction("恢复跟随全局", Icons.Outlined.Restore, { save(config.copy(sources = config.sources - key)) })
            }
            Text("滑到提示后松手执行；拖回或纵向滚动取消。第二段只执行一个动作。稍后读仅适用于本地 RSS；其他来源使用已读或收藏。")
            listOf(-1, 1).forEach { direction ->
                val shallow = choice.action(direction, false); val deep = choice.action(direction, true)
                Text(if (direction < 0) "向左" else "向右", style = MaterialTheme.typography.titleMedium)
                Text("第一段：${shallow.label}")
                Row {
                    listOf(SwipeAction.Read, SwipeAction.ToggleRead, SwipeAction.Star).forEach { action ->
                        IconAction(action.label, if (action == SwipeAction.Star) Icons.Outlined.StarBorder else Icons.Outlined.DoneAll, {
                            val nextDeep = if (action == deep || (action in SwipeChoice.readActions && deep in SwipeChoice.readActions))
                                if (action == SwipeAction.Star) SwipeAction.Read else SwipeAction.Star else deep
                            update(if (direction < 0) choice.copy(left = action, deepLeft = nextDeep) else choice.copy(right = action, deepRight = nextDeep))
                        }, selected = shallow == action)
                    }
                }
                Text("第二段：${deep.label}")
                Row {
                    listOf(SwipeAction.Read, SwipeAction.Star, SwipeAction.ReadLater).filterNot {
                        (!localChoice && it == SwipeAction.ReadLater) || it == shallow || (it in SwipeChoice.readActions && shallow in SwipeChoice.readActions)
                    }.forEach { action -> IconAction(action.label, when (action) {
                        SwipeAction.ReadLater -> Icons.Outlined.BookmarkBorder
                        SwipeAction.Star -> Icons.Outlined.StarBorder
                        else -> Icons.Outlined.Check
                    }, { update(if (direction < 0) choice.copy(deepLeft = action) else choice.copy(deepRight = action)) }, selected = deep == action) }
                }
            }
            Text("撤销仅在本次进程保留；后续标记更改不会被覆盖。", style = MaterialTheme.typography.bodySmall)
            IconAction("完成滑动设置", Icons.Outlined.Check, close)
        }
    }
}
