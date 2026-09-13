package com.huanchengfly.tieba.post.update

import android.util.Log
import com.huanchengfly.tieba.post.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * 更新元数据拉取 + APK 下载。只用 HttpURLConnection（无 token，匿名访问 GitHub API），
 * debug 构建允许通过系统属性覆盖 API 根地址用于本地 mock。
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"

    private const val GITHUB_API_ROOT = "https://api.github.com/repos/min09577/TiebaLite"

    /** 更新检查默认 API 根地址（GitHub，仅 min09577/TiebaLite Releases）。 */
    const val DEFAULT_API_ROOT = GITHUB_API_ROOT

    /** debug 构建可覆盖的 API 根地址（本地 mock 用），release 恒为官方地址。 */
    val apiRoot: String
        get() = if (BuildConfig.DEBUG) {
            System.getProperty("tieba.update.apiRoot") ?: GITHUB_API_ROOT
        } else {
            GITHUB_API_ROOT
        }

    /**
     * stable 通道端点：GitHub 语义上的最新非预发布 release。
     * App 侧二次校验 tag 不含 -ai.（防 CI 漏加 --prerelease 导致 stable 通道拿到预发布包）。
     */
    fun fetchStableRelease(root: String = apiRoot): GitHubRelease? {
        val json = httpGet("$root/releases/latest") ?: return null
        val release = GitHubRelease.fromJson(JSONObject(json)) ?: return null
        return if (release.isAiTag) null else release
    }

    /**
     * ai 通道端点：recent releases 中过滤 prerelease==true 且 tag 含 -ai.，取最新者。
     * 排序必须 base 优先（base 高者恒胜，preVer 仅在同 base 内比较）：
     * 只比 preVer 的话，base 升级窗口内 4.0.0-ai.51 会压过 4.0.1-ai.1 选出旧 base，
     * 经 compare 判 Equal → UI 显示已是最新，新 base 推送被静默屏蔽（每个 base 升级必触发）。
     */
    fun fetchLatestAiRelease(root: String = apiRoot): GitHubRelease? {
        val json = httpGet("$root/releases?per_page=10") ?: return null
        return GitHubRelease.fromJsonArray(json)
            .filter { it.isPrerelease && it.isAiTag }
            .mapNotNull { release ->
                VersionCompat.parse(release.tagName)?.let { release to it }
            }
            .maxWithOrNull(
                compareBy({ it.second.base }, { it.second.preReleaseVer ?: 0 })
            )
            ?.first
    }

    /**
     * 下载 APK 到目标文件（调用方保证父目录已存在）。
     * 流式下载同时计算 SHA-256，完成后与 release 资产 digest（若提供）比对，
     * 不匹配则删除半成品并报错。
     */
    fun downloadApk(
        release: GitHubRelease,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadResult {
        val url = release.apkUrl ?: return DownloadResult.Error("没有可用的安装包")
        try {
            val conn = openConnection(url) ?: return DownloadResult.Error("无法连接服务器")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                return DownloadResult.Error("下载失败（HTTP $code）")
            }
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            DigestOutputStream(FileOutputStream(target), digest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = release.apkDigest?.removePrefix("sha256:")?.lowercase()
            if (!expected.isNullOrEmpty() && actual != expected) {
                target.delete()
                return DownloadResult.Error("安装包校验失败")
            }
            return DownloadResult.Success(target, actual)
        } catch (e: IOException) {
            target.delete()
            return DownloadResult.Error(e.message ?: "下载失败")
        } catch (e: Exception) {
            Log.w(TAG, "downloadApk failed", e)
            target.delete()
            return DownloadResult.Error("下载失败")
        } finally {
            runCatching { }
        }
    }

    private fun openConnection(url: String): HttpURLConnection? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TiebaLite-Update")
            }
        } catch (e: Exception) {
            Log.w(TAG, "openConnection failed: $e")
            null
        }
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = openConnection(url) ?: return null
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $url")
                return null
            }
            conn.inputStream.use { it.bufferedReader().readText() }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet failed: $e")
            null
        }
    }

    private const val DEFAULT_BUFFER_SIZE = 8192
}

sealed class DownloadResult {
    data class Success(val file: File, val sha256: String) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}
