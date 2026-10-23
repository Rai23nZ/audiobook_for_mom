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
import ru.lesnoychtets.app.data.AppPrefs
import ru.lesnoychtets.app.data.CatalogBook
import ru.lesnoychtets.app.data.GoogleDriveFolder
import ru.lesnoychtets.app.data.LibraryRepository
import ru.lesnoychtets.app.data.LibrivoxDownloader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "downloads"

private fun foregroundInfo(context: Context, id: Int, text: String): ForegroundInfo {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Загрузка книг", NotificationManager.IMPORTANCE_LOW))
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Лесной Чтец")
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

private fun networkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(if (AppPrefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .build()

/** Синхронизация семейной папки Google Drive. */
class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val link = AppPrefs.driveLink
        if (link.isBlank()) return Result.success()
        AppPrefs.syncRunning.value = true
        tryForeground(1001, "Синхронизация семейной папки")
        return try {
            val r = GoogleDriveFolder.sync(applicationContext, link) { AppPrefs.setSyncMessage("⏳ $it") }
            LibraryRepository.rescan(applicationContext)
            val time = SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date())
            AppPrefs.setSyncMessage(
                "✓ $time: в папке ${r.total} файлов, скачано новых ${r.downloaded}" +
                    (if (r.removed > 0) ", удалено ${r.removed}" else ""),
            )
            Result.success()
        } catch (e: Exception) {
            AppPrefs.setSyncMessage("⚠ Ошибка синхронизации: ${e.message}")
            LibraryRepository.rescan(applicationContext)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            AppPrefs.syncRunning.value = false
        }
    }

    companion object {
        private const val PERIODIC = "drive_sync_periodic"
        private const val NOW = "drive_sync_now"

        /** Регулярная проверка папки раз в 6 часов (по умолчанию — только по Wi-Fi). */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (AppPrefs.driveLink.isBlank()) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
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
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"

        fun workName(id: String) = "librivox_$id"

        fun enqueue(context: Context, book: CatalogBook) {
            val request = OneTimeWorkRequestBuilder<LibrivoxDownloadWorker>()
                .setConstraints(networkConstraints())
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
