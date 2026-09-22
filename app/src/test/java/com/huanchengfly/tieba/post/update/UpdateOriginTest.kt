package com.huanchengfly.tieba.post.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 更新渠道（平台来源）与 stable 通道选版：
 * - rootFor/otherOrigin：Codeberg 为默认主渠道，GitHub 为镜像/回退渠道
 * - pickLatestStable：列表筛选替代 /releases/latest（Codeberg 的 latest 排除 prerelease，
 *   只有预发布时 404），判据剔除 prerelease 与 ai tag，base 优先取最大
 */
class UpdateOriginTest {

    private fun rel(tag: String, prerelease: Boolean = false) = GitHubRelease(
        tagName = tag, name = tag, htmlUrl = "", body = "",
        isPrerelease = prerelease, apkUrl = null, apkName = null, apkDigest = null
    )

    // ---- 平台来源映射 ----

    @Test
    fun `rootFor maps codeberg and github to their api roots`() {
        assertEquals(
            "https://codeberg.org/api/v1/repos/min09577/TiebaLite",
            UpdateDownloader.rootFor(UpdateOrigin.CODEBERG)
        )
        assertEquals(
            "https://api.github.com/repos/min09577/TiebaLite",
            UpdateDownloader.rootFor(UpdateOrigin.GITHUB)
        )
    }

    @Test
    fun `default origin is codeberg`() {
        assertEquals(UpdateOrigin.CODEBERG, DEFAULT_ORIGIN)
    }

    @Test
    fun `otherOrigin flips both ways`() {
        assertEquals(UpdateOrigin.GITHUB, UpdateDownloader.otherOrigin(UpdateOrigin.CODEBERG))
        assertEquals(UpdateOrigin.CODEBERG, UpdateDownloader.otherOrigin(UpdateOrigin.GITHUB))
    }

    // ---- stable 通道候选集选版 ----

    @Test
    fun `stable picks max base among non-prerelease non-ai`() {
        val selected = pickLatestStable(
            listOf(rel("v4.0.0"), rel("v4.0.1"), rel("v3.9.0"))
        )
        assertEquals("v4.0.1", selected?.tagName)
    }

    @Test
    fun `stable excludes prerelease and ai tags`() {
        // Codeberg 的真实 ai release 是 prerelease=true；GitHub 侧 ai release
        // prerelease=false——两个判据都必须有，否则会选到预发布
        val selected = pickLatestStable(
            listOf(
                rel("v4.0.0-ai.51", prerelease = true),
                rel("v4.0.0-ai.50", prerelease = false),
                rel("v4.0.0", prerelease = false)
            )
        )
        assertEquals("v4.0.0", selected?.tagName)
    }

    @Test
    fun `stable base wins over higher prerelease ver of older base`() {
        val selected = pickLatestStable(
            listOf(rel("v4.0.0-ai.99", prerelease = true), rel("v4.0.1"))
        )
        assertEquals("v4.0.1", selected?.tagName)
    }

    @Test
    fun `stable unparseable tags and empty list yield null`() {
        assertNull(pickLatestStable(emptyList()))
        assertNull(pickLatestStable(listOf(rel("garbage-tag"))))
        assertNull(pickLatestStable(listOf(rel("v4.0.0-ai.1", prerelease = true))))
    }
}
