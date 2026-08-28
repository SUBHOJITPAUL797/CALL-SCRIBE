package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        versionTag: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val cleanTag = versionTag.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val targetFile = File(updateDir, "CallScribe-$cleanTag.apk")

            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("Accept", "application/octet-stream")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to download APK: HTTP ${response.code}")
                )
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val totalBytes = body.contentLength()

            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                inputStream = body.byteStream()
                outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalDownloaded = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalDownloaded += bytesRead
                    if (totalBytes > 0) {
                        val progress = (totalDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        onProgress(progress)
                    }
                }
                outputStream.flush()
                Result.success(targetFile)
            } finally {
                inputStream?.close()
                outputStream?.close()
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(context, "Downloaded APK file is invalid.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Cannot launch installer: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun openBrowserReleasePage(context: Context, releasePageUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releasePageUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open browser.", Toast.LENGTH_SHORT).show()
        }
    }
}
