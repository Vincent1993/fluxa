package com.fluxa.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxa.app.data.local.*
import com.fluxa.app.data.reader.*
import com.fluxa.app.domain.usecase.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class ReaderActionsDeviceTest {
    @Test fun localUseCasesRetainNotesHighlightsTagsAndProgressAfterReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reader-device-fixture.db"; context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, FluxaDatabase::class.java, name).build()
        var db = open()
        try {
            db.articleDao().upsertAll(listOf(ArticleEntity("fixture", "Title", "Feed", 1, false, false, "<p>Real cached body</p>")))
            val local = LocalReadingRepository(db, context)
            AddNoteUseCase(local)("fixture", "Typed note")
            AddHighlightUseCase(local)("fixture", "cached body", "Context")
            local.tags("fixture", listOf("Read", "Journal")); local.progress("fixture", .61f)
            SaveForLaterUseCase(local)("fixture"); ArchiveArticleUseCase(local)("fixture")
            db.close(); db = open()
            val article = db.observeReaderArticles().first().single()
            assertEquals(2, article.annotationCount); assertEquals(.61f, article.readingProgress, .001f)
            assertEquals(listOf("Journal", "Read"), article.tags.sorted())
            assertNotNull(article.archivedAtEpochSeconds); assertNotNull(article.savedAtEpochSeconds)
            assertEquals("Typed note", db.readerDao().observeAnnotations("fixture").first().first { it.kind == "note" }.note)
            assertEquals(listOf("MarkRead"), db.pendingActionDao().getAllOrdered().map { it.actionType })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
