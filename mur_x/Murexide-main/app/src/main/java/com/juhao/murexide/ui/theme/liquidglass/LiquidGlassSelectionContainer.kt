/*
 * Selection plumbing is adapted from AndroidX Compose Foundation 1.10.4.
 * Copyright 2021 The Android Open Source Project. Licensed under Apache 2.0.
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.juhao.murexide.ui.theme.liquidglass

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.internal.isWriteSupported
import androidx.compose.foundation.internal.toClipEntry
import androidx.compose.foundation.text.ContextMenuArea
import androidx.compose.foundation.text.detectDownAndDragGesturesWithObserver
import androidx.compose.foundation.text.rememberClipboardEventsHandler
import androidx.compose.foundation.text.Handle
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.SelectedTextType
import androidx.compose.foundation.text.selection.Selection
import androidx.compose.foundation.text.selection.SelectionContainer as PlatformSelectionContainer
import androidx.compose.foundation.text.selection.SelectionHandle
import androidx.compose.foundation.text.selection.SelectionManager
import androidx.compose.foundation.text.selection.SelectionRegistrarImpl
import androidx.compose.foundation.text.selection.SimpleLayout
import androidx.compose.foundation.text.selection.calculateSelectionMagnifierCenterAndroid
import androidx.compose.foundation.text.selection.rememberPlatformSelectionBehaviors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
internal fun LiquidGlassSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (LocalLiquidGlassMagnifierController.current == null) {
        PlatformSelectionContainer(modifier = modifier, content = content)
        return
    }

    var selection by remember { mutableStateOf<Selection?>(null) }
    LiquidGlassSelectionContainer(
        modifier = modifier,
        selection = selection,
        onSelectionChange = { selection = it },
        content = content
    )
}

@Composable
private fun LiquidGlassSelectionContainer(
    modifier: Modifier,
    selection: Selection?,
    onSelectionChange: (Selection?) -> Unit,
    content: @Composable () -> Unit
) {
    val registrar = rememberSaveable(saver = SelectionRegistrarImpl.Saver) {
        SelectionRegistrarImpl()
    }
    val manager = remember { SelectionManager(registrar) }
    val owner = remember { Any() }
    val magnifierController = LocalLiquidGlassMagnifierController.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    manager.hapticFeedBack = LocalHapticFeedback.current
    manager.onCopyHandler = remember(coroutineScope, clipboard) {
        if (clipboard.isWriteSupported()) {
            { textToCopy ->
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    clipboard.setClipEntry(textToCopy.toClipEntry())
                }
            }
        } else {
            null
        }
    }
    manager.textToolbar = LocalTextToolbar.current
    manager.onSelectionChange = onSelectionChange
    manager.selection = selection
    @OptIn(ExperimentalFoundationApi::class)
    if (ComposeFoundationFlags.isSmartSelectionEnabled) {
        manager.platformSelectionBehaviors =
            rememberPlatformSelectionBehaviors(SelectedTextType.StaticText, null)
        manager.coroutineScope = coroutineScope
    }

    rememberClipboardEventsHandler(
        onCopy = { manager.getSelectedText() },
        isEnabled = manager.isNonEmptySelection()
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    val magnifierSize = with(density) {
        IntSize(
            LiquidGlassMagnifierSize.width.roundToPx(),
            LiquidGlassMagnifierSize.height.roundToPx()
        )
    }
    val activeHandle = manager.draggingHandle
    val currentDragPosition = manager.currentDragPosition
    val sourceCenter = if (activeHandle != null && currentDragPosition != null) {
        calculateSelectionMagnifierCenterAndroid(manager, magnifierSize)
    } else {
        Offset.Unspecified
    }
    val lineHeight = when (activeHandle) {
        Handle.SelectionStart -> manager.startHandleLineHeight
        Handle.SelectionEnd -> manager.endHandleLineHeight
        else -> with(density) { 24.dp.toPx() }
    }
    PublishLiquidGlassMagnifier(
        owner = owner,
        position = sourceCenter.takeIf { it.isSpecified }?.let {
            LiquidGlassMagnifierPosition(it, lineHeight)
        },
        sourceCoordinates = manager.containerLayoutCoordinates
    )

    SimpleLayout(
        modifier = modifier.then(manager.modifier.withoutPlatformMagnifier())
    ) {
        ContextMenuArea(manager) {
            CompositionLocalProvider(LocalSelectionRegistrar provides registrar) {
                content()
                if (
                    manager.isInTouchMode &&
                    manager.hasFocus &&
                    !manager.isTriviallyCollapsedSelection()
                ) {
                    manager.selection?.let { currentSelection ->
                        listOf(true, false).fastForEach { isStartHandle ->
                            val observer = remember(isStartHandle) {
                                manager.handleDragObserver(isStartHandle)
                            }
                            val positionProvider: () -> Offset = remember(isStartHandle) {
                                if (isStartHandle) {
                                    { manager.startHandlePosition ?: Offset.Unspecified }
                                } else {
                                    { manager.endHandlePosition ?: Offset.Unspecified }
                                }
                            }
                            val direction = if (isStartHandle) {
                                currentSelection.start.direction
                            } else {
                                currentSelection.end.direction
                            }
                            val handleLineHeight = if (isStartHandle) {
                                manager.startHandleLineHeight
                            } else {
                                manager.endHandleLineHeight
                            }

                            SelectionHandle(
                                offsetProvider = positionProvider,
                                isStartHandle = isStartHandle,
                                direction = direction,
                                handlesCrossed = currentSelection.handlesCrossed,
                                lineHeight = handleLineHeight,
                                modifier = Modifier.pointerInput(observer) {
                                    detectDownAndDragGesturesWithObserver(observer)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(manager, owner) {
        onDispose {
            magnifierController?.hide(owner)
            manager.onRelease()
            manager.hasFocus = false
        }
    }
}

internal fun Modifier.withoutPlatformMagnifier(): Modifier =
    foldIn<Modifier>(Modifier) { result, element ->
        // selectionMagnifier() is a composed modifier at this point. Removing it before
        // materialization prevents Compose from creating the platform MagnifierElement.
        if (element.javaClass.name == ComposeComposedModifierClassName) {
            result
        } else {
            result.then(element)
        }
    }

private const val ComposeComposedModifierClassName = "androidx.compose.ui.ComposedModifier"
