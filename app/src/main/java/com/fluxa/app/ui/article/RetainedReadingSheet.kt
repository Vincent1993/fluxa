package com.fluxa.app.ui.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxa.app.data.reader.LocalReadingRepository
import com.fluxa.app.domain.model.Article
import com.fluxa.app.ui.components.ActionRow
import com.fluxa.app.ui.components.IconAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RetainedReadingViewModel @Inject constructor(val local: LocalReadingRepository) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    fun edit(block: suspend LocalReadingRepository.() -> Unit) = viewModelScope.launch {
        try { local.block(); error.value = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error.value = e.message ?: "无法保存本地记录" }
    }
}

/** Keeps published NewsBlur notes, tags and archive state reachable in the new reader. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetainedReadingSheet(article: Article, close: () -> Unit, model: RetainedReadingViewModel = hiltViewModel()) {
    val records by remember(article.id) { model.local.annotations(article.id) }.collectAsStateWithLifecycle(emptyList())
    val error by model.error.collectAsStateWithLifecycle()
    var tags by rememberSaveable(article.id) { mutableStateOf(article.tags.joinToString(", ")) }
    var editor by rememberSaveable(article.id) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().padding(horizontal = 20.dp)) {
            item {
                Text("本地阅读记录", style = MaterialTheme.typography.titleLarge)
                Text("仅保存在本机，不发送给 NewsBlur。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ActionRow("添加本地笔记", Icons.Outlined.EditNote, { editor = "note" })
                ActionRow("添加本地高亮", Icons.Outlined.Highlight, { editor = "highlight" })
                ActionRow(if (article.savedAtEpochSeconds == null) "稍后读" else "取消稍后读", Icons.Outlined.BookmarkBorder,
                    { model.edit { save(article.id) } }, selected = article.savedAtEpochSeconds != null)
                ActionRow(if (article.archivedAtEpochSeconds == null) "归档文章" else "恢复文章", Icons.Outlined.Archive,
                    { model.edit { archive(article.id) } }, selected = article.archivedAtEpochSeconds != null)
                OutlinedTextField(tags, { tags = it }, label = { Text("本地标签，用逗号分隔") }, modifier = Modifier.fillMaxWidth())
                IconAction("保存本地标签", Icons.Outlined.Check, { model.edit { this.tags(article.id, tags.split(',', '，')) } })
            }
            items(records, key = { it.id }) { record ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Column(Modifier.weight(1f).clickable { editor = "edit/" + record.id }) {
                        if (record.quote.isNotBlank()) Text(record.quote)
                        if (record.note.isNotBlank()) Text(record.note)
                    }
                    IconAction("删除本地记录", Icons.Outlined.DeleteOutline, { model.edit { deleteAnnotation(record.id) } })
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
    if (editor != null) {
        val editing = records.firstOrNull { editor == "edit/" + it.id }
        val highlight = editor == "highlight" || editing?.kind == "highlight"
        var quote by rememberSaveable(editor) { mutableStateOf(editing?.quote.orEmpty()) }
        var note by rememberSaveable(editor) { mutableStateOf(editing?.note.orEmpty()) }
        AlertDialog(onDismissRequest = { editor = null }, title = { Text(if (highlight) "本地高亮" else "本地笔记") },
            text = { Column {
                if (highlight) OutlinedTextField(quote, { quote = it }, label = { Text("从正文复制需要高亮的文字") })
                OutlinedTextField(note, { note = it }, label = { Text(if (highlight) "备注（可选）" else "笔记") })
            } }, confirmButton = {
                IconAction("保存本地记录", Icons.Outlined.Check, {
                    model.edit {
                        if (editing != null) editAnnotation(editing.id, quote, note)
                        else if (highlight) this.highlight(article.id, quote, note) else this.note(article.id, note)
                    }
                    editor = null
                }, enabled = if (highlight) quote.isNotBlank() else note.isNotBlank())
            }, dismissButton = { IconAction("取消", Icons.Outlined.Close, { editor = null }) })
    }
}
