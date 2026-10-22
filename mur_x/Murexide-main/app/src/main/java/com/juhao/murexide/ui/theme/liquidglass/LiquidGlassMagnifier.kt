/*
 * Magnifier rendering adapted from AndroidLiquidGlass's catalog application.
 * Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
 */
package com.juhao.murexide.ui.theme.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.shapes.Capsule
import kotlin.math.roundToInt

internal val LiquidGlassMagnifierSize = DpSize(128.dp, 96.dp)

internal data class LiquidGlassMagnifierPosition(
    val center: Offset,
    val lineHeightPx: Float
)

internal data class LiquidGlassMagnifierRequest(
    val owner: Any,
    val sourceCenterInWindow: Offset,
    val lineHeightPx: Float
)

internal class LiquidGlassMagnifierController {
    var request by mutableStateOf<LiquidGlassMagnifierRequest?>(null)
        private set

    fun show(
        owner: Any,
        sourceCenterInWindow: Offset,
        lineHeightPx: Float
    ) {
        val next = LiquidGlassMagnifierRequest(
            owner = owner,
            sourceCenterInWindow = sourceCenterInWindow,
            lineHeightPx = lineHeightPx
        )
        if (request != next) request = next
    }

    fun hide(owner: Any) {
        if (request?.owner === owner) request = null
    }
}

internal val LocalLiquidGlassMagnifierController =
    staticCompositionLocalOf<LiquidGlassMagnifierController?> { null }

@Composable
internal fun LiquidGlassMagnifierHost(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        Box(modifier = modifier, content = { content() })
        return
    }

    val controller = remember { LiquidGlassMagnifierController() }
    val baseBackdrop = rememberLayerBackdrop()
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates = it }
    ) {
        Box(Modifier.layerBackdrop(baseBackdrop)) {
            CompositionLocalProvider(
                LocalLiquidGlassMagnifierController provides controller,
                content = content
            )
        }

        val request = controller.request
        val hostCoordinates = coordinates
        if (request != null && hostCoordinates?.isAttached == true) {
            val sourceCenter = hostCoordinates.windowToLocal(request.sourceCenterInWindow)
            LiquidGlassMagnifierOverlay(
                sourceCenter = sourceCenter,
                baseBackdrop = baseBackdrop,
                lineHeightPx = request.lineHeightPx,
                containerCoordinates = hostCoordinates
            )
        }
    }
}

@Composable
internal fun PublishLiquidGlassMagnifier(
    owner: Any,
    position: LiquidGlassMagnifierPosition?,
    sourceCoordinates: LayoutCoordinates?
) {
    val controller = LocalLiquidGlassMagnifierController.current

    SideEffect {
        val center = position?.center
        if (
            controller != null &&
            center != null &&
            center.isSpecified &&
            sourceCoordinates?.isAttached == true
        ) {
            controller.show(
                owner = owner,
                sourceCenterInWindow = sourceCoordinates.localToWindow(center),
                lineHeightPx = position.lineHeightPx
            )
        } else {
            controller?.hide(owner)
        }
    }

    DisposableEffect(controller, owner) {
        onDispose { controller?.hide(owner) }
    }
}

@Composable
private fun LiquidGlassMagnifierOverlay(
    sourceCenter: Offset,
    baseBackdrop: Backdrop,
    lineHeightPx: Float,
    containerCoordinates: LayoutCoordinates
) {
    if (!sourceCenter.isSpecified) return

    val density = LocalDensity.current
    val view = LocalView.current
    val lensWidthPx = with(density) { LiquidGlassMagnifierSize.width.roundToPx() }
    val lensHeightPx = with(density) { LiquidGlassMagnifierSize.height.roundToPx() }
    val sourceToLensDistancePx = with(density) { 80.dp.toPx() }
    val edgePaddingPx = with(density) { 8.dp.toPx() }
    val cursorWidthPx = with(density) { 4.dp.roundToPx() }
    val cursorHeightPx = lineHeightPx
        .coerceAtLeast(with(density) { 18.dp.toPx() })
        .coerceAtMost(with(density) { 32.dp.toPx() })
        .roundToInt()

    val containerPosition = containerCoordinates.positionInWindow()
    val sourceCenterInWindow = containerPosition + sourceCenter
    val halfLensWidth = lensWidthPx / 2f
    val halfLensHeight = lensHeightPx / 2f
    val lensCenterInWindow = Offset(
        x = clampCenter(
            value = sourceCenterInWindow.x,
            halfSize = halfLensWidth,
            availableSize = view.width.toFloat(),
            padding = edgePaddingPx
        ),
        y = verticalLensCenter(
            sourceY = sourceCenterInWindow.y,
            distance = sourceToLensDistancePx,
            halfHeight = halfLensHeight,
            availableHeight = view.height.toFloat(),
            padding = edgePaddingPx
        )
    )
    val lensCenter = lensCenterInWindow - containerPosition
    val lensOffsetFromSource = lensCenter - sourceCenter

    val cursorColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val cursorOnDraw: ContentDrawScope.() -> Unit = remember(cursorColor) {
        {
            drawRect(cursorColor)
        }
    }
    val cursorBackdrop = rememberLayerBackdrop(onDraw = cursorOnDraw)
    val combinedBackdrop = rememberCombinedBackdrop(
        baseBackdrop,
        cursorBackdrop
    )

    Layout(
        modifier = Modifier.clearAndSetSemantics { },
        content = {
            Box(
                Modifier
                    .layerBackdrop(cursorBackdrop)
                    .size(
                        width = with(density) { cursorWidthPx.toDp() },
                        height = with(density) { cursorHeightPx.toDp() }
                    )
            )
            Box(
                Modifier
                    .drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { Capsule() },
                        effects = {
                            lens(
                                16.dp.toPx(),
                                48.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = true
                            )
                        },
                        innerShadow = { InnerShadow(radius = 16.dp) },
                        onDrawBackdrop = { drawBackdrop ->
                            withTransform(
                                {
                                    scale(1.5f, 1.5f)
                                    translate(
                                        left = lensOffsetFromSource.x,
                                        top = lensOffsetFromSource.y
                                    )
                                },
                                drawBackdrop
                            )
                        }
                    )
                    .size(LiquidGlassMagnifierSize)
            )
        }
    ) { measurables, constraints ->
        val cursor = measurables[0].measure(
            Constraints.fixed(cursorWidthPx, cursorHeightPx)
        )
        val magnifier = measurables[1].measure(
            Constraints.fixed(lensWidthPx, lensHeightPx)
        )

        layout(constraints.minWidth, constraints.minHeight) {
            cursor.place(
                x = (sourceCenter.x - cursorWidthPx / 2f).roundToInt(),
                y = (sourceCenter.y - cursorHeightPx / 2f).roundToInt()
            )
            magnifier.place(
                x = (lensCenter.x - halfLensWidth).roundToInt(),
                y = (lensCenter.y - halfLensHeight).roundToInt()
            )
        }
    }
}

private fun clampCenter(
    value: Float,
    halfSize: Float,
    availableSize: Float,
    padding: Float
): Float {
    val minimum = halfSize + padding
    val maximum = availableSize - halfSize - padding
    return if (maximum >= minimum) value.coerceIn(minimum, maximum) else availableSize / 2f
}

private fun verticalLensCenter(
    sourceY: Float,
    distance: Float,
    halfHeight: Float,
    availableHeight: Float,
    padding: Float
): Float {
    val minimum = halfHeight + padding
    val maximum = availableHeight - halfHeight - padding
    val above = sourceY - distance
    val below = sourceY + distance
    return when {
        above >= minimum -> above.coerceAtMost(maximum)
        below <= maximum -> below.coerceAtLeast(minimum)
        maximum >= minimum -> above.coerceIn(minimum, maximum)
        else -> availableHeight / 2f
    }
}
