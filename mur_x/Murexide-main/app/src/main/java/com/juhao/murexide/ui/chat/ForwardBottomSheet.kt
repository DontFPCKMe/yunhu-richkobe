package com.juhao.murexide.ui.chat

import com.juhao.murexide.ui.icons.AppIcons
import com.juhao.murexide.ui.icons.AutoMirroredIcon

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.juhao.murexide.ui.components.Avatar

private val ForwardSelectedGreen = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardBottomSheet(
    state: ForwardUiState,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onTargetClick: (com.juhao.murexide.data.ForwardTarget) -> Unit,
    onSend: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = { if (!state.isSending) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "转发消息",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (!state.isSending) {
                        IconButton(onClick = onDismiss) {
                            Icon(AppIcons.Close, contentDescription = "关闭")
                        }
                    }
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    enabled = !state.isLocked && !state.isSending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(AppIcons.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty() && !state.isLocked) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(AppIcons.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                    placeholder = { Text("搜索会话") },
                    shape = RoundedCornerShape(18.dp)
                )

                if (state.error != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.targets.isEmpty() && !state.isLocked) {
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                    }
                }

                when {
                    state.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.filteredTargets.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.query.isBlank()) "暂无可转发的会话" else "未找到相关会话",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                top = 4.dp,
                                bottom = 96.dp
                            )
                        ) {
                            items(
                                items = state.filteredTargets,
                                key = { "${it.chatType}:${it.chatId}" }
                            ) { target ->
                                ForwardTargetRow(
                                    target = target,
                                    selected = target.key in state.selectedKeys,
                                    enabled = !state.isLocked && !state.isSending,
                                    onClick = { onTargetClick(target) }
                                )
                            }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = state.selectedKeys.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp),
                enter = scaleIn(
                    initialScale = 0.68f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(animationSpec = tween(120)),
                exit = scaleOut(
                    targetScale = 0.78f,
                    animationSpec = tween(120)
                ) + fadeOut(animationSpec = tween(90))
            ) {
                val fabIsActive = state.canSend || state.isSending
                FloatingActionButton(
                    onClick = { if (state.canSend) onSend() },
                    shape = CircleShape,
                    containerColor = if (fabIsActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (fabIsActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    if (state.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = if (fabIsActive) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            strokeWidth = 2.dp
                        )
                    } else {
                        AutoMirroredIcon(AppIcons.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardTargetRow(
    target: com.juhao.murexide.data.ForwardTarget,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            target.isPinned -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        animationSpec = tween(180),
        label = "forward_target_background"
    ).value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            Avatar(url = target.avatarUrl, size = 52.dp)
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = scaleIn(
                    initialScale = 0.42f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(animationSpec = tween(100)),
                exit = scaleOut(
                    targetScale = 0.5f,
                    animationSpec = tween(100)
                ) + fadeOut(animationSpec = tween(80))
            ) {
                Surface(
                    modifier = Modifier.size(22.dp),
                    shape = CircleShape,
                    color = ForwardSelectedGreen,
                    contentColor = Color.White
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            AppIcons.Check,
                            contentDescription = "已选择",
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = target.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
