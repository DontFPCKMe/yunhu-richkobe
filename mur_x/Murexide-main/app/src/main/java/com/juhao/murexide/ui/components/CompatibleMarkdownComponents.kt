package com.juhao.murexide.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes.Companion.EOL

/**
 * Recompiles the block quote drawing code against the Compose version resolved by the app.
 *
 * The precompiled block quote in markdown-renderer 0.43.0 targets an older DrawScope ABI and
 * crashes with NoSuchMethodError when a Markdown article containing a quote is opened.
 */
internal fun compatibleMarkdownComponents(): MarkdownComponents = markdownComponents(
    blockQuote = { CompatibleMarkdownBlockQuote(it) },
    checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) }
)

@Composable
private fun CompatibleMarkdownBlockQuote(model: MarkdownComponentModel) {
    val style = model.typography.quote
    val quoteColor = style.color.takeIf { it.isSpecified } ?: LocalMarkdownColors.current.text
    val quoteThickness = LocalMarkdownDimens.current.blockQuoteThickness
    val quotePadding = LocalMarkdownPadding.current.blockQuote
    val quoteTextPadding = LocalMarkdownPadding.current.blockQuoteText
    val quoteBarPadding = LocalMarkdownPadding.current.blockQuoteBar
    val components = LocalMarkdownComponents.current
    val accessibilityLabel = LocalMarkdownA11yLabels.current.blockquote

    Column(
        modifier = Modifier
            .semantics { contentDescription = accessibilityLabel }
            .drawBehind {
                val x = quoteBarPadding.calculateStartPadding(layoutDirection).toPx()
                drawLine(
                    color = quoteColor,
                    start = Offset(x, quoteBarPadding.calculateTopPadding().toPx()),
                    end = Offset(x, size.height - quoteBarPadding.calculateBottomPadding().toPx()),
                    strokeWidth = quoteThickness.toPx()
                )
            }
            .padding(quotePadding)
    ) {
        val emptyLineHeight = with(LocalDensity.current) {
            when {
                style.lineHeight.isSp -> style.lineHeight.toDp()
                style.fontSize.isSp -> style.fontSize.toDp()
                else -> quoteTextPadding.calculateTopPadding() +
                    quoteTextPadding.calculateBottomPadding()
            }
        }
        var previousChildWasQuote = false

        model.node.children.forEachIndexed { index, child ->
            key(child.startOffset) {
                when (child.type) {
                    MarkdownElementTypes.BLOCK_QUOTE -> {
                        if (!previousChildWasQuote && index != 0) {
                            Spacer(Modifier.height(quoteTextPadding.calculateBottomPadding()))
                        }
                        CompatibleMarkdownBlockQuote(model.copy(node = child))
                        previousChildWasQuote = true
                    }

                    EOL -> Spacer(Modifier.height(emptyLineHeight))

                    else -> {
                        if (index == 0 || previousChildWasQuote) {
                            Spacer(Modifier.height(quoteTextPadding.calculateTopPadding()))
                        }
                        previousChildWasQuote = false
                        MarkdownElement(
                            node = child,
                            components = components,
                            content = model.content,
                            includeSpacer = false
                        )
                        if (index == model.node.children.lastIndex) {
                            Spacer(Modifier.height(quoteTextPadding.calculateBottomPadding()))
                        }
                    }
                }
            }
        }
    }
}
