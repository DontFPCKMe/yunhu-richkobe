/*
 * Adapted from the AndroidLiquidGlass catalog application.
 * Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
 * Modified for Murexide with Material fallback, discrete steps, and accessibility.
 */
package com.juhao.murexide.ui.theme.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.juhao.murexide.ui.theme.LocalLiquidGlassBackdrop
import com.juhao.murexide.ui.theme.LocalLiquidGlassBlur
import com.juhao.murexide.ui.theme.LocalLiquidGlassEnabled
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.roundToInt

internal fun snapSliderValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val coerced = value.coerceIn(valueRange)
    if (steps <= 0) return coerced
    val intervals = steps + 1
    val interval = (valueRange.endInclusive - valueRange.start) / intervals
    if (interval == 0f) return valueRange.start
    return valueRange.start +
        (((coerced - valueRange.start) / interval).roundToInt() * interval)
            .coerceIn(0f, valueRange.endInclusive - valueRange.start)
}

@Composable
fun LiquidGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val liquidGlassEnabled = LocalLiquidGlassEnabled.current
    val backdrop = LocalLiquidGlassBackdrop.current
    val blurScale = LocalLiquidGlassBlur.current
    if (!liquidGlassEnabled || backdrop == null) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
        return
    }

    LiquidSliderContent(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        backdrop = backdrop,
        blurScale = blurScale,
        modifier = modifier,
    )
}

@Composable
private fun LiquidSliderContent(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChangeFinished: (() -> Unit)?,
    backdrop: Backdrop,
    blurScale: Float,
    modifier: Modifier,
) {
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val accentColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.secondary
    val trackBackdrop = rememberLayerBackdrop()
    val snappedValue = snapSliderValue(value, valueRange, steps)
    val currentValue by rememberUpdatedState(snappedValue)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(snappedValue, valueRange, steps)
                if (!enabled) disabled()
                setProgress { requested ->
                    if (!enabled) return@setProgress false
                    val newValue = snapSliderValue(requested, valueRange, steps)
                    if (newValue != snappedValue) {
                        onValueChange(newValue)
                        onValueChangeFinished?.invoke()
                    }
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = constraints.maxWidth.coerceAtLeast(1)
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(
            animationScope,
            valueRange,
            steps,
            trackWidth,
            isLtr,
        ) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = snappedValue,
                valueRange = valueRange,
                visibilityThreshold = ((valueRange.endInclusive - valueRange.start) / 1000f)
                    .coerceAtLeast(0.0001f),
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = { didDrag = false },
                onDragStopped = {
                    if (didDrag) {
                        val settledValue = snapSliderValue(targetValue, valueRange, steps)
                        if (settledValue != targetValue) animateToValue(settledValue)
                        currentOnValueChange(settledValue)
                        currentOnValueChangeFinished?.invoke()
                    }
                },
                onDrag = { _, dragAmount ->
                    if (!enabled) return@DampedDragAnimation
                    if (!didDrag) didDrag = dragAmount.x != 0f
                    val delta = (valueRange.endInclusive - valueRange.start) *
                        (dragAmount.x / trackWidth)
                    val dragTarget =
                        (if (isLtr) targetValue + delta else targetValue - delta)
                            .coerceIn(valueRange)
                    dragToValue(dragTarget)
                    val nextValue = snapSliderValue(dragTarget, valueRange, steps)
                    if (nextValue != currentValue) currentOnValueChange(nextValue)
                },
            )
        }

        LaunchedEffect(dampedDragAnimation, snappedValue) {
            if (!dampedDragAnimation.isDragging && dampedDragAnimation.targetValue != snappedValue) {
                dampedDragAnimation.updateValue(snappedValue)
            }
        }

        Box(Modifier.layerBackdrop(trackBackdrop).align(Alignment.CenterStart)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .then(
                        if (enabled) {
                            Modifier.pointerInput(animationScope, valueRange, steps) {
                                detectTapGestures { position ->
                                    val fraction = position.x / trackWidth
                                    val rawValue = if (isLtr) {
                                        valueRange.start +
                                            (valueRange.endInclusive - valueRange.start) * fraction
                                    } else {
                                        valueRange.endInclusive -
                                            (valueRange.endInclusive - valueRange.start) * fraction
                                    }
                                    val target = snapSliderValue(rawValue, valueRange, steps)
                                    dampedDragAnimation.animateToValue(target)
                                    currentOnValueChange(target)
                                    currentOnValueChangeFinished?.invoke()
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .height(6.dp)
                    .fillMaxWidth(),
            )

            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress)
                            .fastRoundToInt()
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    },
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) *
                            if (isLtr) 1f else -1f
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            scale(lerp(2f / 3f, 1f, progress), lerp(0f, 1f, progress)) {
                                drawBackdrop()
                            }
                        },
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(4.dp.toPx() * blurScale * (1f - progress))
                        lens(
                            10.dp.toPx() * progress,
                            14.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = if (isLightTheme) {
                        {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress,
                            )
                        }
                    } else {
                        null
                    },
                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 1f - dampedDragAnimation.pressProgress))
                    },
                )
                .size(24.dp, 24.dp)
                .align(Alignment.CenterStart),
        )
    }
}

private fun Color.luminance(): Float =
    (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)
