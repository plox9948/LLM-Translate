package com.plox.llmtranslate.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты парсера JSON релиза GitHub Releases API (issue #95).
 */
class UpdateReleaseParserTest {

    private val parser = UpdateReleaseParser()

    private fun releaseJson(
        tag: String = "v0.6.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assetsJson: String = """
            [
              {"name": "changelog.txt", "content_type": "text/plain",
               "browser_download_url": "https://example.com/changelog.txt"},
              {"name": "app-release.apk", "content_type": "application/vnd.android.package-archive",
               "browser_download_url": "https://example.com/app-release.apk"}
            ]
        """
    ) = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "html_url": "https://github.com/plox9948/LLM-Translate/releases/tag/$tag",
          "assets": $assetsJson
        }
    """

    @Test
    fun `valid release with apk asset parsed by content type`() {
        val result = parser.parse(releaseJson())
        assertTrue(result is ReleaseParseResult.Release)
        val info = (result as ReleaseParseResult.Release).info
        assertEquals("v0.6.0", info.tagName)
        assertEquals("0.6.0", info.version)
        assertEquals("https://github.com/plox9948/LLM-Translate/releases/tag/v0.6.0", info.releaseUrl)
        assertEquals("https://example.com/app-release.apk", info.apkUrl)
        assertEquals("app-release.apk", info.assetName)
    }

    @Test
    fun `apk fallback by name extension when no content type`() {
        val json = releaseJson(
            assetsJson = """
                [
                  {"name": "notes.md", "content_type": "text/plain",
                   "browser_download_url": "https://example.com/notes.md"},
                  {"name": "LLM-Translate.apk", "content_type": "application/octet-stream",
                   "browser_download_url": "https://example.com/LLM-Translate.apk"}
                ]
            """
        )
        val result = parser.parse(json)
        assertTrue(result is ReleaseParseResult.Release)
        assertEquals(
            "https://example.com/LLM-Translate.apk",
            (result as ReleaseParseResult.Release).info.apkUrl
        )
    }

    @Test
    fun `content type match wins over earlier apk-name asset`() {
        val json = releaseJson(
            assetsJson = """
                [
                  {"name": "debug.apk", "content_type": "application/octet-stream",
                   "browser_download_url": "https://example.com/debug.apk"},
                  {"name": "release-package", "content_type": "application/vnd.android.package-archive",
                   "browser_download_url": "https://example.com/release-package"}
                ]
            """
        )
        val result = parser.parse(json)
        assertTrue(result is ReleaseParseResult.Release)
        assertEquals(
            "https://example.com/release-package",
            (result as ReleaseParseResult.Release).info.apkUrl
        )
    }

    @Test
    fun `draft release is not an update`() {
        assertEquals(ReleaseParseResult.NotStable, parser.parse(releaseJson(draft = true)))
    }

    @Test
    fun `prerelease is not an update`() {
        assertEquals(ReleaseParseResult.NotStable, parser.parse(releaseJson(prerelease = true)))
    }

    @Test
    fun `release without assets has no apk`() {
        assertEquals(ReleaseParseResult.NoAsset, parser.parse(releaseJson(assetsJson = "[]")))
    }

    @Test
    fun `release with only non-apk assets has no apk`() {
        val json = releaseJson(
            assetsJson = """
                [{"name": "notes.txt", "content_type": "text/plain",
                  "browser_download_url": "https://example.com/notes.txt"}]
            """
        )
        assertEquals(ReleaseParseResult.NoAsset, parser.parse(json))
    }

    @Test
    fun `broken json is parse error`() {
        assertEquals(ReleaseParseResult.ParseError, parser.parse("not a json {"))
        assertEquals(ReleaseParseResult.ParseError, parser.parse(""))
    }

    @Test
    fun `release without tag is parse error`() {
        val json = """{"draft": false, "prerelease": false, "assets": []}"""
        assertEquals(ReleaseParseResult.ParseError, parser.parse(json))
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """
            {
              "tag_name": "v1.0.0",
              "upload_url": "https://example.com/x",
              "node_id": "RE_kwAAA",
              "draft": false,
              "prerelease": false,
              "html_url": "https://example.com/release",
              "assets": [
                {"name": "a.apk", "content_type": "application/vnd.android.package-archive",
                 "browser_download_url": "https://example.com/a.apk", "id": 1, "size": 123}
              ],
              "unexpected": {"nested": [1, 2, 3]}
            }
        """
        assertTrue(parser.parse(json) is ReleaseParseResult.Release)
    }
}
