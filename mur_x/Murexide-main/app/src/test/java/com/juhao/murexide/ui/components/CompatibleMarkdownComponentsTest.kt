package com.juhao.murexide.ui.components

import com.mikepenz.markdown.compose.components.CurrentComponentsBridge
import org.junit.Assert.assertNotSame
import org.junit.Test

class CompatibleMarkdownComponentsTest {

    @Test
    fun blockQuoteRenderer_doesNotUseBinaryIncompatibleDefault() {
        val components = compatibleMarkdownComponents()

        assertNotSame(CurrentComponentsBridge.blockQuote, components.blockQuote)
    }
}
