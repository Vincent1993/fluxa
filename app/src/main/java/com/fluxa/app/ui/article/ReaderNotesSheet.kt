package com.fluxa.app.ui.article

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxa.app.data.directrss.DirectReaderNote
import com.fluxa.app.data.directrss.ReaderQuotes
import com.fluxa.app.ui.components.IconAction
import kotlinx.coroutines.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderNotesSheet(articleId: String, mode: String, html: String, notes: List<DirectReaderNote>,
    save: suspend (DirectReaderNote) -> Unit, delete: suspend (String) -> Unit, onClose: () -> Unit) {
    var paragraphs by remember(html) { mutableStateOf(emptyList<ReaderQuotes.Paragraph>()) }
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DirectReaderNote?>(null) }
    var draft by remember { mutableStateOf("") }
    var deleted by remember { mutableStateOf<DirectReaderNote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(html) {
        try { paragraphs = withContext(Dispatchers.Default) { ReaderQuotes.paragraphs(html) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "正文不支持段落选择" }
    }
    fun operate(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "笔记操作失败" }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("段落摘录与笔记", style = MaterialTheme.typography.titleLarge)
                Text("选择已保存正文中的完整段落。RSS、全文和保存网页分别定位；不是浏览器任意选区。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it) }
                IconAction("关闭笔记", Icons.Outlined.Close, onClose)
            }
            val edit = editing
            if (edit != null) {
                item {
                    Text("已选择的原文段落")
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(edit.quote) }
                    OutlinedTextField(draft, { draft = it.take(4000) }, label = { Text("笔记（可留空，仅高亮）") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        IconAction("确认摘录并保存笔记", Icons.Outlined.Check, { operate { save(edit.copy(note = draft)); editing = null; choosing = false } }, enabled = !busy)
                        IconAction("取消笔记编辑", Icons.Outlined.Close, { editing = null }, enabled = !busy)
                    }
                }
            } else if (choosing) {
                item { if (paragraphs.isEmpty()) Text("此正文没有可选择的完整段落") }
                items(paragraphs, key = { "${it.hash}:${it.occurrence}" }) { paragraph ->
                    Row {
                        Text(paragraph.text, Modifier.weight(1f))
                        IconAction("选择段落 ${paragraph.occurrence + 1} ${paragraph.text.take(40)}", Icons.Outlined.Add, {
                            editing = DirectReaderNote(UUID.randomUUID().toString(), articleId, mode, paragraph.text,
                                paragraph.hash, paragraph.occurrence, paragraph.occurrences, "")
                            draft = ""
                        })
                    }
                }
            } else {
                item { IconAction("选择正文段落", Icons.Outlined.Add, { choosing = true }) }
                items(notes, key = { it.id }) { note ->
                    Column {
                        Text("${note.mode} · ${if (note.mode == mode && paragraphs.any { ReaderQuotes.matches(note, it) }) "原文已定位" else "未在当前正文定位，保留摘录"}", style = MaterialTheme.typography.labelMedium)
                        Text(note.quote); if (note.note.isNotBlank()) Text(note.note)
                        Row {
                            IconAction("编辑笔记 ${note.id}", Icons.Outlined.Edit, { editing = note; draft = note.note }, enabled = !busy)
                            IconAction("删除笔记 ${note.id}", Icons.Outlined.Delete, { operate { delete(note.id); deleted = note } }, enabled = !busy)
                        }
                    }
                }
                deleted?.let { note -> item {
                    IconAction("撤销删除笔记", Icons.Outlined.Undo, { operate { save(note); deleted = null } }, enabled = !busy)
                } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
