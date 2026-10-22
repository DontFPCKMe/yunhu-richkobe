package com.juhao.murexide.ui.components.litehtml

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juhao.murexide.ui.components.buildMessageCss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method

@RunWith(AndroidJUnit4::class)
class LiteHtmlViewDetailsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun nativeSummaryTapExpandsAndCollapsesDetails() {
        lateinit var view: LiteHtmlView
        instrumentation.runOnMainSync {
            view = LiteHtmlView(instrumentation.targetContext)
        }
        val native = NativeMethods(view)
        val handle = native.create(
            html = """
                <details>
                  <summary>Details</summary>
                  <div style="height:160px">Expanded content</div>
                </details>
            """.trimIndent(),
            css = buildMessageCss(
                backgroundColor = Color.White,
                textColor = Color.Black,
                linkColor = Color.Blue,
                codeColor = Color.LightGray,
                borderColor = Color.Gray,
                fontSize = 14f,
                darkTheme = false
            )
        )
        assertTrue(handle != 0L)

        try {
            val collapsedHeight = native.layout(handle)
            assertEquals(3, native.hitTest(handle, SUMMARY_X, SUMMARY_Y))

            native.pointerDown(handle, SUMMARY_X, SUMMARY_Y)
            assertTrue(native.pointerUp(handle, SUMMARY_X, SUMMARY_Y))
            val expandedHeight = native.layout(handle)
            assertTrue(
                "Expected details to grow after expansion: $collapsedHeight -> $expandedHeight",
                expandedHeight > collapsedHeight + 100
            )

            native.pointerDown(handle, SUMMARY_X, SUMMARY_Y)
            assertTrue(native.pointerUp(handle, SUMMARY_X, SUMMARY_Y))
            assertEquals(collapsedHeight, native.layout(handle))
        } finally {
            native.destroy(handle)
        }
    }

    @Test
    fun openAttributeStartsExpanded() {
        lateinit var view: LiteHtmlView
        instrumentation.runOnMainSync {
            view = LiteHtmlView(instrumentation.targetContext)
        }
        val native = NativeMethods(view)
        val css = messageCss()
        val closed = native.create(detailsHtml(open = false), css)
        val openHandle = native.create(detailsHtml(open = true), css)
        assertTrue(closed != 0L)
        assertTrue(openHandle != 0L)

        try {
            assertTrue(native.layout(openHandle) > native.layout(closed) + 100)
        } finally {
            native.destroy(openHandle)
            native.destroy(closed)
        }
    }

    @Test
    fun nestedDetailsToggleIndependently() {
        lateinit var view: LiteHtmlView
        instrumentation.runOnMainSync {
            view = LiteHtmlView(instrumentation.targetContext)
        }
        val native = NativeMethods(view)
        val handle = native.create(
            html = """
                <details>
                  <summary>Outer</summary>
                  <div style="height:20px">Outer content</div>
                  <details>
                    <summary>Inner</summary>
                    <div style="height:120px">Inner content</div>
                  </details>
                </details>
            """.trimIndent(),
            css = messageCss()
        )
        assertTrue(handle != 0L)

        try {
            val collapsedHeight = native.layout(handle)
            native.tap(handle, SUMMARY_X, SUMMARY_Y)
            val outerExpandedHeight = native.layout(handle)
            assertTrue(outerExpandedHeight > collapsedHeight)

            val innerSummaryY = findSecondSummaryY(native, handle, outerExpandedHeight)
            native.tap(handle, SUMMARY_X, innerSummaryY)
            val innerExpandedHeight = native.layout(handle)
            assertTrue(innerExpandedHeight > outerExpandedHeight + 80)

            native.tap(handle, SUMMARY_X, innerSummaryY)
            assertEquals(outerExpandedHeight, native.layout(handle))
            native.tap(handle, SUMMARY_X, SUMMARY_Y)
            assertEquals(collapsedHeight, native.layout(handle))
        } finally {
            native.destroy(handle)
        }
    }

    private fun findSecondSummaryY(
        native: NativeMethods,
        handle: Long,
        documentHeight: Int
    ): Float {
        var foundFirstSummary = false
        var leftFirstSummary = false
        for (y in 0 until documentHeight) {
            val isSummary = native.hitTest(handle, SUMMARY_X, y.toFloat()) == 3
            when {
                isSummary && leftFirstSummary -> return y.toFloat()
                isSummary -> foundFirstSummary = true
                foundFirstSummary -> leftFirstSummary = true
            }
        }
        throw AssertionError("Could not find nested summary hit target")
    }

    private fun messageCss(): String = buildMessageCss(
        backgroundColor = Color.White,
        textColor = Color.Black,
        linkColor = Color.Blue,
        codeColor = Color.LightGray,
        borderColor = Color.Gray,
        fontSize = 14f,
        darkTheme = false
    )

    private fun detailsHtml(open: Boolean): String = """
        <details${if (open) " open" else ""}>
          <summary>Details</summary>
          <div style="height:160px">Expanded content</div>
        </details>
    """.trimIndent()

    private class NativeMethods(private val view: LiteHtmlView) {
        private val create = method(
            "nativeCreate",
            String::class.java,
            String::class.java,
            Float::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )
        private val layout = method(
            "nativeLayout",
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )
        private val hitTest = method(
            "nativeHitTest",
            Long::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!
        )
        private val pointerDown = method(
            "nativePointerDown",
            Long::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!
        )
        private val pointerUp = method(
            "nativePointerUp",
            Long::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!
        )
        private val destroy = method("nativeDestroy", Long::class.javaPrimitiveType!!)

        fun create(html: String, css: String): Long =
            create.invoke(view, html, css, 1f, 14) as Long

        fun layout(handle: Long): Int = layout.invoke(view, handle, DOCUMENT_WIDTH) as Int

        fun hitTest(handle: Long, x: Float, y: Float): Int =
            hitTest.invoke(view, handle, x, y) as Int

        fun pointerDown(handle: Long, x: Float, y: Float) {
            pointerDown.invoke(view, handle, x, y)
        }

        fun pointerUp(handle: Long, x: Float, y: Float): Boolean =
            pointerUp.invoke(view, handle, x, y) as Boolean

        fun tap(handle: Long, x: Float, y: Float) {
            pointerDown(handle, x, y)
            assertTrue(pointerUp(handle, x, y))
        }

        fun destroy(handle: Long) {
            destroy.invoke(view, handle)
        }

        private fun method(name: String, vararg parameterTypes: Class<*>): Method =
            LiteHtmlView::class.java.getDeclaredMethod(name, *parameterTypes).apply {
                isAccessible = true
            }
    }

    private companion object {
        const val DOCUMENT_WIDTH = 320
        const val SUMMARY_X = 32f
        const val SUMMARY_Y = 14f
    }
}
