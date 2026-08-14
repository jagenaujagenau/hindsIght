package com.warmly.watchrecorder.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Clips waiting to reach the phone. The watch is the temporary holder, never the
 * archive: a clip is deleted the moment the Data Layer confirms delivery.
 */
object ClipOutbox {

    private const val UNIQUE_WORK = "clip-upload"

    fun directory(context: Context): File =
        File(context.filesDir, "outbox").apply { mkdirs() }

    fun pending(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.extension == "m4a" }
            ?.sortedBy { it.name }
            .orEmpty()

    fun enqueueUpload(context: Context) {
        val request = OneTimeWorkRequestBuilder<ClipUploadWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
