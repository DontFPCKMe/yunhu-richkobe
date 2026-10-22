package com.juhao.murexide.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juhao.murexide.data.ForwardTarget
import com.juhao.murexide.data.filterForwardTargets
import com.juhao.murexide.data.mergeForwardTargets
import com.juhao.murexide.repository.ConversationRepository
import com.juhao.murexide.repository.ForwardReceiveRequest
import com.juhao.murexide.repository.MessageRepository
import com.juhao.murexide.ui.theme.UiCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ForwardUiState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val targets: List<ForwardTarget> = emptyList(),
    val query: String = "",
    val selectedKeys: Set<Pair<Int, String>> = emptySet(),
    val sourceMsgIds: List<String> = emptyList(),
    val sourceChatType: Int = 1,
    val nextSourceIndex: Int = 0,
    val isSending: Boolean = false,
    val isLocked: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null
) {
    val filteredTargets: List<ForwardTarget>
        get() = targets.filterForwardTargets(query)

    val selectedTargets: List<ForwardTarget>
        get() = targets.filter { it.key in selectedKeys }

    val pendingSourceCount: Int
        get() = (sourceMsgIds.size - nextSourceIndex).coerceAtLeast(0)

    val canSend: Boolean
        get() = selectedKeys.isNotEmpty() && pendingSourceCount > 0 && !isSending && !isCompleted
}

sealed interface ForwardEvent {
    data class SourceForwarded(val msgId: String) : ForwardEvent
    data class Completed(val recipients: List<ForwardTarget>) : ForwardEvent
}

class ForwardViewModel(
    private val token: String,
    private val messageRepository: MessageRepository = MessageRepository(),
    private val conversationRepository: ConversationRepository = ConversationRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(ForwardUiState())
    val uiState: StateFlow<ForwardUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ForwardEvent>()
    val events: SharedFlow<ForwardEvent> = _events.asSharedFlow()

    fun open(sourceChatType: Int, sourceMsgIds: List<String>) {
        val validIds = sourceMsgIds.filter(String::isNotBlank).distinct()
        _uiState.value = ForwardUiState(
            isOpen = true,
            isLoading = true,
            sourceMsgIds = validIds,
            sourceChatType = sourceChatType
        )
        loadTargets()
    }

    fun close() {
        if (_uiState.value.isSending) return
        _uiState.value = ForwardUiState()
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query, error = null) }
    }

    fun retryLoad() {
        if (_uiState.value.isLoading || _uiState.value.isSending) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadTargets()
    }

    fun toggleTarget(target: ForwardTarget) {
        _uiState.update { state ->
            if (state.isSending || state.isLocked || state.isCompleted) return@update state
            val selected = if (target.key in state.selectedKeys) {
                state.selectedKeys - target.key
            } else {
                state.selectedKeys + target.key
            }
            state.copy(selectedKeys = selected, error = null)
        }
    }

    fun send() {
        val snapshot = _uiState.value
        if (!snapshot.canSend) return

        val recipients = snapshot.selectedTargets.map {
            ForwardReceiveRequest(chatId = it.chatId, chatType = it.chatType)
        }
        val recipientTargets = snapshot.selectedTargets
        _uiState.update {
            it.copy(isSending = true, isLocked = true, error = null)
        }

        viewModelScope.launch {
            var index = _uiState.value.nextSourceIndex
            while (index < snapshot.sourceMsgIds.size) {
                val msgId = snapshot.sourceMsgIds[index]
                val result = messageRepository.forwardMessage(
                    token = token,
                    msgId = msgId,
                    sourceChatType = snapshot.sourceChatType,
                    recipients = recipients
                )
                if (result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "转发失败"
                    val completedCount = index
                    _uiState.update {
                        it.copy(
                            nextSourceIndex = index,
                            isSending = false,
                            isLocked = true,
                            error = if (completedCount == 0) {
                                error
                            } else {
                                "已转发 $completedCount/${snapshot.sourceMsgIds.size} 条，$error"
                            }
                        )
                    }
                    return@launch
                }

                _events.emit(ForwardEvent.SourceForwarded(msgId))
                index += 1
                _uiState.update { it.copy(nextSourceIndex = index) }
            }

            _uiState.update {
                it.copy(isSending = false, isLocked = true, isCompleted = true, error = null)
            }
            _events.emit(ForwardEvent.Completed(recipientTargets))
        }
    }

    private fun loadTargets() {
        viewModelScope.launch {
            val cachedConversations = UiCache.conversation.value
            val cachedSticky = UiCache.stickyConversations.value
            if (cachedConversations.isNotEmpty() && cachedSticky.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        targets = mergeForwardTargets(cachedConversations, cachedSticky)
                    )
                }
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                val conversations = cachedConversations.ifEmpty {
                    conversationRepository.getConversationList(token).getOrNull().orEmpty()
                }
                val sticky = cachedSticky.ifEmpty {
                    conversationRepository.getStickyList(token).getOrNull().orEmpty()
                }
                conversations to sticky
            }
            UiCache.stickyConversations.value = result.second
            val targets = mergeForwardTargets(result.first, result.second)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    targets = targets,
                    error = if (targets.isEmpty()) "暂无可转发的会话" else null
                )
            }
        }
    }
}
