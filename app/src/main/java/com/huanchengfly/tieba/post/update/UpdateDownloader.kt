package com.huanchengfly.tieba.post.update

import android.util.Log
import com.huanchengfly.tieba.post.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * 更新检查平台来源（用户可在关于页选择，DataStore 持久化）。
 * 主渠道为 GitHub（qaqmin09577），Codeberg 为镜像/备用：任一端发了新版本都应能检测到。
 */
enum class UpdateOrigin {
    CODEBERG,
    GITHUB
}

/** 默认平台来源：GitHub（当前主构建线）。 */
val DEFAULT_ORIGIN = UpdateOrigin.GITHUB

/**
 * 更新元数据拉取 + APK 下载。只用 HttpURLConnection（无 token，匿名访问 Releases API），
 * debug 构建允许通过系统属性覆盖 API 根地址用于本地 mock。
 */
object UpdateDownloader {

    private const val TAG = "UpdateDownloader"

    private const val CODEBERG_API_ROOT = "https://codeberg.org/api/v1/repos/min09577/TiebaLite"

    private const val GITHUB_API_ROOT = "https://api.github.com/repos/qaqmin09577/TiebaLite"

    /**
     * 各平台 Releases API 根地址。Gitea/Forgejo 的 release/asset 字段名与 GitHub 一致
     * （tag_name/name/html_url/body/prerelease/assets[].browser_download_url），
     * 解析与下载逻辑两端通用，只有根地址不同。
     */
    fun rootFor(origin: UpdateOrigin): String = when (origin) {
        UpdateOrigin.CODEBERG -> CODEBERG_API_ROOT
        UpdateOrigin.GITHUB -> GITHUB_API_ROOT
    }

    /** 另一平台来源（启动检查主渠道失败时静默回退用）。 */
    fun otherOrigin(origin: UpdateOrigin): UpdateOrigin = when (origin) {
        UpdateOrigin.CODEBERG -> UpdateOrigin.GITHUB
        UpdateOrigin.GITHUB -> UpdateOrigin.CODEBERG
    }

    /** debug 构建可覆盖的 API 根地址（本地 mock 用），release 恒为官方地址。 */
    val apiRoot: String
        get() = if (BuildConfig.DEBUG) {
            System.getProperty("tieba.update.apiRoot") ?: rootFor(DEFAULT_ORIGIN)
        } else {
            rootFor(DEFAULT_ORIGIN)
        }

    /**
     * stable 通道端点：recent releases 中筛"非预发布且非 ai tag"取版本最大者。
     * 不用 /releases/latest：Codeberg (Forgejo) 的 latest 语义排除 prerelease，
     * 平台只有预发布时该端点 404（v4.0.0-ai.51 发布时实测）；列表筛选两端行为一致。
     * App 侧仍二次剔除 ai tag（GitHub 的 ai release prerelease=false，只按
     * isPrerelease 筛会漏，该护栏是 stable/ai 通道分离的最后防线）。
     */
    fun fetchStableRelease(root: String = apiRoot): GitHubRelease? {
        val json = httpGet("${root.trimEnd('/')}/releases?per_page=10") ?: return null
        return pickLatestStable(GitHubRelease.fromJsonArray(json))
    }

    /**
     * ai 通道端点：recent releases 中筛 ai tag（tag 含 -ai.）取最新者。
     * 判据只认 isAiTag 不认 isPrerelease：真实 CI 未加 --prerelease，
     * 真实 ai release 的 prerelease=false，按 isPrerelease 筛会把它滤空、
     * 两通道同时空转（P0-2 实测）。版本解析失败与 stable tag 由 [pickLatestAi] 统一剔除。
     */
    fun fetchLatestAiRelease(root: String = apiRoot): GitHubRelease? {
        // trimEnd('/')：mockRoot/自定义根尾带斜杠时防 "$root/..." 产生 // 双斜杠 404
        val json = httpGet("${root.trimEnd('/')}/releases?per_page=10") ?: return null
        return pickLatestAi(GitHubRelease.fromJsonArray(json))
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

/**
 * ai 通道候选集选版（生产侧纯函数，可 JVM 单测）：
 * 1. 只留 tag 含 -ai. 的候选（isAiTag——不依赖 CI 是否加 --prerelease，真实 CI 未加）；
 * 2. tag 解析失败的剔除；
 * 3. base 优先排序取最大：base 高者恒胜，preVer 仅在同 base 内比较
 *    （只比 preVer 时 base 升级窗口内 4.0.0-ai.51 会压过 4.0.1-ai.1 选出旧 base，
 *    经 compare 判 Equal → 新 base 推送被静默屏蔽，每个 base 升级必触发）。
 */
fun pickLatestAi(candidates: List<GitHubRelease>): GitHubRelease? {
    return candidates.asSequence()
        .filter { it.isAiTag }
        .mapNotNull { release ->
            VersionCompat.parse(release.tagName)?.let { release to it }
        }
        .maxWithOrNull(
            compareBy({ it.second.base }, { it.second.preReleaseVer ?: 0 })
        )
        ?.first
}

/**
 * stable 通道候选集选版（生产侧纯函数，可 JVM 单测），判据与 [pickLatestAi] 镜像：
 * 1. 剔除 prerelease（两平台正式版均为非预发布；Codeberg 的 ai release 是 prerelease=true，
 *    仅靠 isPrerelease 剔除会在 GitHub 侧漏掉 prerelease=false 的 ai tag）；
 * 2. 剔除 ai tag；
 * 3. tag 解析失败的剔除；
 * 4. base 优先排序取最大：base 高者恒胜，preVer 仅在同 base 内比较。
 */
fun pickLatestStable(candidates: List<GitHubRelease>): GitHubRelease? {
    return candidates.asSequence()
        .filter { !it.isPrerelease && !it.isAiTag }
        .mapNotNull { release ->
            VersionCompat.parse(release.tagName)?.let { release to it }
        }
        .maxWithOrNull(
            compareBy({ it.second.base }, { it.second.preReleaseVer ?: 0 })
        )
        ?.first
}

sealed class DownloadResult {
    data class Success(val file: File, val sha256: String) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}
