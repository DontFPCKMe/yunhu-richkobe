package com.juhao.murexide.ui.components.litehtml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlRenderPolicyTest {
    @Test
    fun acceptsHtmlUpToUtf8ByteLimit() {
        assertTrue(HtmlRenderPolicy.canRender("a".repeat(HtmlRenderPolicy.MAX_HTML_BYTES)))
        assertFalse(HtmlRenderPolicy.canRender("好".repeat(HtmlRenderPolicy.MAX_HTML_BYTES / 3 + 1)))
    }

    @Test
    fun onlyAllowsExplicitStaticImageSchemes() {
        assertTrue(HtmlRenderPolicy.isAllowedImageUrl("https://example.com/a.webp"))
        assertTrue(HtmlRenderPolicy.isAllowedImageUrl("HTTP://example.com/a.png"))
        assertTrue(HtmlRenderPolicy.isAllowedImageUrl("data:image/png;base64,AAAA"))
        assertFalse(HtmlRenderPolicy.isAllowedImageUrl("file:///data/user/0/private.png"))
        assertFalse(HtmlRenderPolicy.isAllowedImageUrl("content://private/image"))
        assertFalse(HtmlRenderPolicy.isAllowedImageUrl("javascript:alert(1)"))
        assertFalse(HtmlRenderPolicy.isAllowedImageUrl("../relative.png"))
    }

    @Test
    fun onlyYunhuSchemeStaysInsideApplication() {
        assertTrue(HtmlRenderPolicy.isInternalDeepLink("yunhu://post-detail?id=1"))
        assertFalse(HtmlRenderPolicy.isInternalDeepLink("https://example.com"))
        assertFalse(HtmlRenderPolicy.isInternalDeepLink("intent://other-app"))
    }
}

