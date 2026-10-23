package ru.lesnoychtets.app.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ru.lesnoychtets.app.data.CatalogBook
import ru.lesnoychtets.app.data.GoogleDriveFolder
import ru.lesnoychtets.app.data.LibraryRepository
import ru.lesnoychtets.app.data.LibrivoxDownloader
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "downloads"

const val PROGRESS_DONE = "done"
const val PROGRESS_TOTAL = "total"

private fun foregroundInfo(context: Context, id: Int, text: String): ForegroundInfo {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Загрузка книг", NotificationManager.IMPORTANCE_LOW))
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Вслух")
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .build()
    return if (Build.VERSION.SDK_INT >= 29) {
        ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(id, notification)
    }
}

/** Пытаемся работать как видимая загрузка, чтобы большие книги не обрывались через 10 минут. */
private suspend fun CoroutineWorker.tryForeground(id: Int, text: String) {
    try {
        setForeground(foregroundInfo(applicationContext, id, text))
    } catch (_: Exception) {
        // Из фона Android может запретить — тогда просто качаем как обычная фоновая задача.
    }
}

/** Всё скачивается только по Wi-Fi (сеть без ограничения трафика). */
private fun wifiOnly(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .build()

/** Обновление списка книг семейной папки Google Drive (без скачивания самих файлов). */
class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val count = GoogleDriveFolder.refresh(applicationContext)
            LibraryRepository.rescan(applicationContext)
            Result.success(workDataOf(KEY_COUNT to count))
        } catch (e: Exception) {
            LibraryRepository.rescan(applicationContext)
            Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "drive_sync_periodic"
        const val NOW = "drive_sync_now"
        const val KEY_COUNT = "count"

        /** Список проверяется при запуске приложения и раз в 6 часов — по Wi-Fi. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val periodic = PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(wifiOnly())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            syncNow(context, ExistingWorkPolicy.KEEP)
        }

        fun syncNow(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>().setConstraints(wifiOnly()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
        }
    }
}

/** Скачивание одной книги из семейной папки — только когда её попросили кнопкой. */
class DriveBookDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_BOOK) ?: return Result.failure()
        val book = LibraryRepository.find(key) ?: return Result.failure()
        tryForeground(key.hashCode(), "Скачивается: ${book.title}")
        return try {
            GoogleDriveFolder.downloadBook(applicationContext, book) { done, total ->
                setProgressAsync(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
            }
            LibraryRepository.rescan(applicationContext)
            Result.success()
        } catch (e: Exception) {
            LibraryRepository.rescan(applicationContext)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "drive_book"
        const val KEY_TAG_PREFIX = "book:"
        private const val KEY_BOOK = "book"

        fun enqueue(context: Context, bookKey: String) {
            val request = OneTimeWorkRequestBuilder<DriveBookDownloadWorker>()
                .setConstraints(wifiOnly())
                .addTag(TAG)
                .addTag(KEY_TAG_PREFIX + bookKey)
                .setInputData(workDataOf(KEY_BOOK to bookKey))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("drive_book_$bookKey", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Удаление из телефона книг, дослушанных накануне и раньше. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        LibraryRepository.removeListened(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("cleanup_listened", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Скачивание одной книги из каталога LibriVox. */
class LibrivoxDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val book = CatalogBook(
            id = inputData.getString(KEY_ID) ?: return Result.failure(),
            author = inputData.getString(KEY_AUTHOR).orEmpty(),
            title = inputData.getString(KEY_TITLE).orEmpty(),
            year = inputData.getInt(KEY_YEAR, 0).takeIf { it > 0 },
        )
        tryForeground(book.id.hashCode(), "Скачивается: ${book.title}")
        return try {
            LibrivoxDownloader.download(applicationContext, book) { done, total ->
                setProgressAsync(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
            }
            LibraryRepository.rescan(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "librivox"
        const val KEY_ID = "id"
        const val ID_TAG_PREFIX = "lv:"
        private const val KEY_AUTHOR = "author"
        private const val KEY_TITLE = "title"
        private const val KEY_YEAR = "year"

        fun workName(id: String) = "librivox_$id"

        fun enqueue(context: Context, book: CatalogBook) {
            val request = OneTimeWorkRequestBuilder<LibrivoxDownloadWorker>()
                .setConstraints(wifiOnly())
                .addTag(TAG)
                .addTag(ID_TAG_PREFIX + book.id)
                .setInputData(
                    workDataOf(
                        KEY_ID to book.id,
                        KEY_AUTHOR to book.author,
                        KEY_TITLE to book.title,
                        KEY_YEAR to (book.year ?: 0),
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(book.id), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        }
    }
}
