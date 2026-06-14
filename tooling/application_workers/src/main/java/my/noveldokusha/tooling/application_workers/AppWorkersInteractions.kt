package my.noveldokusha.tooling.application_workers

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import my.noveldokusha.interactor.WorkersInteractions
import my.noveldokusha.core.domain.LibraryCategory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AppWorkersInteractions @Inject constructor(
    private val workManager: WorkManager
): WorkersInteractions {

    override fun checkForLibraryUpdates(libraryCategory: LibraryCategory) {
        workManager.beginUniqueWork(
            LibraryUpdatesWorker.TAG_MANUAL,
            ExistingWorkPolicy.REPLACE,
            LibraryUpdatesWorker.createManualRequest(updateCategory = libraryCategory)
        ).enqueue()
    }

    override fun cancelLibraryUpdates() {
        workManager.cancelUniqueWork(LibraryUpdatesWorker.TAG_MANUAL)
        workManager.cancelAllWorkByTag(LibraryUpdatesWorker.TAG)
    }

    override fun isManualUpdateRunning(): Flow<Boolean> {
        return workManager.getWorkInfosForUniqueWorkFlow(LibraryUpdatesWorker.TAG_MANUAL)
            .map { workInfos ->
                workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
            }
    }
}
