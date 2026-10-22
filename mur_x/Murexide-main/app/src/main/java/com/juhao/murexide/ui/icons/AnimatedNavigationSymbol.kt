package com.juhao.murexide.ui.icons

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val FILL_DURATION_MILLIS = 240
private const val SELECTED_SCALE_START = 0.88f
private const val SELECTED_SCALE_DAMPING = 0.72f
private const val SELECTED_SCALE_STIFFNESS = 500f

@Composable
fun AnimatedNavigationSymbol(
    outlineIcon: ImageVector,
    filledIcon: ImageVector,
    selected: Boolean,
    contentDescription: String,
    tint: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
) {
    val fillProgress = remember(outlineIcon, filledIcon) {
        Animatable(if (selected) 1f else 0f)
    }
    val scale = remember(outlineIcon, filledIcon) { Animatable(1f) }
    var initialized by remember(outlineIcon, filledIcon) { mutableStateOf(false) }

    LaunchedEffect(selected) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                fillProgress.animateTo(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = FILL_DURATION_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                if (selected) {
                    scale.snapTo(SELECTED_SCALE_START)
                    scale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = SELECTED_SCALE_DAMPING,
                            stiffness = SELECTED_SCALE_STIFFNESS,
                        ),
                    )
                } else {
                    scale.snapTo(1f)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    ) {
        Icon(
            imageVector = outlineIcon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .drawWithContent {
                    val progress = fillProgress.value.coerceIn(0f, 1f)
                    clipRect(bottom = size.height * (1f - progress)) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
        Icon(
            imageVector = filledIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .drawWithContent {
                    val progress = fillProgress.value.coerceIn(0f, 1f)
                    clipRect(top = size.height * (1f - progress)) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
    }
}
