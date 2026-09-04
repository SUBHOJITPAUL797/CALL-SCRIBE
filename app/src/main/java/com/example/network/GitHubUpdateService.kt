package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class GitHubRelease(
    @field:Json(name = "tag_name") val tagName: String,
    @field:Json(name = "name") val name: String? = null,
    @field:Json(name = "body") val body: String? = null,
    @field:Json(name = "html_url") val htmlUrl: String,
    @field:Json(name = "published_at") val publishedAt: String? = null,
    @field:Json(name = "assets") val assets: List<GitHubAsset> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GitHubAsset(
    @field:Json(name = "name") val name: String,
    @field:Json(name = "browser_download_url") val browserDownloadUrl: String,
    @field:Json(name = "size") val size: Long = 0L,
    @field:Json(name = "content_type") val contentType: String? = null
)

data class AppUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersionName: String,
    val latestVersionClean: String,  // e.g. "1.2.1" (no "v" prefix)
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String?,
    val releasePageUrl: String,
    val assetSize: Long
)

interface GitHubApiService {
    @Headers("Accept: application/vnd.github.v3+json")
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubRelease
}

object GitHubRetrofitClient {
    private const val GITHUB_BASE_URL = "https://api.github.com/"

    val service: GitHubApiService by lazy {
        Retrofit.Builder()
            .baseUrl(GITHUB_BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GitHubApiService::class.java)
    }
}

object VersionComparator {
    /** Strip "v"/"V" prefix and anything after "-" (e.g. "-beta") */
    fun cleanVersion(v: String): String =
        v.trim().removePrefix("v").removePrefix("V").split("-")[0].trim()

    /**
     * Returns true only if [latest] is STRICTLY NEWER than [current].
     * Pads shorter version to same length as longer (e.g. "1.2" == "1.2.0").
     */
    fun isNewer(current: String, latest: String): Boolean {
        val curParts = cleanVersion(current).split(".").mapNotNull { it.toIntOrNull() }
        val latParts = cleanVersion(latest).split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(curParts.size, latParts.size)
        for (i in 0 until maxLen) {
            val curNum = curParts.getOrElse(i) { 0 }
            val latNum = latParts.getOrElse(i) { 0 }
            if (latNum > curNum) return true
            if (latNum < curNum) return false
        }
        return false // identical
    }

    /** Returns true if [a] and [b] represent the same version (after cleaning) */
    fun isSameVersion(a: String, b: String): Boolean =
        cleanVersion(a) == cleanVersion(b)
}

class GitHubUpdateRepository(
    private val owner: String = "SUBHOJITPAUL797",
    private val repo: String = "CALL-SCRIBE"
) {
    suspend fun checkForUpdate(
        currentVersion: String,
        skippedVersion: String? = null
    ): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val release = GitHubRetrofitClient.service.getLatestRelease(owner, repo)
            val cleanLatest = VersionComparator.cleanVersion(release.tagName)
            val isNewer = VersionComparator.isNewer(currentVersion, release.tagName)

            // If user previously skipped this exact version, don't nag them again
            val isSkipped = skippedVersion != null &&
                VersionComparator.isSameVersion(skippedVersion, release.tagName)

            val apkAsset = release.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true)
            } ?: release.assets.firstOrNull()

            val info = AppUpdateInfo(
                hasUpdate = isNewer && !isSkipped,
                latestVersionName = release.tagName,
                latestVersionClean = cleanLatest,
                releaseTitle = release.name ?: "Release ${release.tagName}",
                releaseNotes = release.body ?: "No release notes provided.",
                downloadUrl = apkAsset?.browserDownloadUrl,
                releasePageUrl = release.htmlUrl,
                assetSize = apkAsset?.size ?: 0L
            )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
