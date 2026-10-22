package com.juhao.murexide.ui.components.litehtml

internal object HtmlRenderPolicy {
    const val MAX_HTML_BYTES: Int = 1024 * 1024

    fun canRender(html: String): Boolean =
        html.toByteArray(Charsets.UTF_8).size <= MAX_HTML_BYTES

    fun isAllowedImageUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("data:image/", ignoreCase = true)

    fun isInternalDeepLink(url: String): Boolean = url.startsWith("yunhu://")
}

