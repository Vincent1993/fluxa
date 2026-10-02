package com.fluxa.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.fluxa.app.data.sync.*
import org.junit.Test
import org.junit.Assert.*

class BackgroundSyncDeviceTest {
    @Test fun applicationWorkerConfigurationSchedulesOneJobAndCancelsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = BackgroundSyncSettings(context)
        val scheduler = BackgroundSyncScheduler(context, settings)
        val manager = WorkManager.getInstance(context)
        try {
            scheduler.setEnabled(true)
            scheduler.setEnabled(true)
            val work = manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.WORK_NAME).get()
                .filter { !it.state.isFinished }
            assertEquals(1, work.size)
            assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
            assertTrue(BackgroundSyncSettings(context).enabled)
            scheduler.restore()
            assertEquals(work.single().id, manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.WORK_NAME).get()
                .single { !it.state.isFinished }.id)
        } finally {
            scheduler.setEnabled(false)
            manager.cancelUniqueWork(BackgroundSyncScheduler.WORK_NAME).result.get()
        }
        assertFalse(settings.enabled)
        assertTrue(manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.WORK_NAME).get().all { it.state.isFinished })
    }
}
