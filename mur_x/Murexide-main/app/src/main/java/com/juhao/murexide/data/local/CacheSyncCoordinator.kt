package com.juhao.murexide.data.local

import android.util.Log
import com.juhao.murexide.data.ActiveConversationRegistry
import com.juhao.murexide.data.ConversationKey
import com.juhao.murexide.network.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/** Persists complete real-time messages even while no chat screen is in the foreground. */
class CacheSyncCoordinator(
    private val webSocketManager: WebSocketManager = WebSocketManager.getInstance(),
    private val dismissConversationNotification: suspend (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "CacheSyncCoordinator"
        private const val READ_RECEIPT_DEBOUNCE_MS = 250L
    }

    private val readReceiptJobs = ConcurrentHashMap<ConversationKey, Job>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            ActiveConversationRegistry.readRequests.collect { conversation ->
                val accountId = LocalCache.currentAccountId() ?: return@collect
                LocalCache.clearUnread(
                    accountId = accountId,
                    chatId = conversation.chatId,
                    chatType = conversation.chatType
                )
                scheduleReadReceipt(scope, conversation)
            }
        }

        scope.launch {
            webSocketManager.messageFlow.collect { event ->
                val accountId = LocalCache.currentAccountId() ?: return@collect
                when (event) {
                    is WebSocketManager.WsEvent.NewMessage -> {
                        val activeConversation = ActiveConversationRegistry.activeKeyFor(event.message)
                        LocalCache.cacheMessages(accountId, listOf(event.message))
                        LocalCache.applyNewMessageToConversation(
                            accountId = accountId,
                            message = event.message,
                            incrementUnread = ActiveConversationRegistry
                                .shouldIncrementUnread(event.message)
                        )
                        activeConversation?.let(ActiveConversationRegistry::requestRead)
                    }
                    is WebSocketManager.WsEvent.LocalMessageSent,
                    is WebSocketManager.WsEvent.LatestMessageResolved -> {
                        val message = when (event) {
                            is WebSocketManager.WsEvent.LocalMessageSent -> event.message
                            is WebSocketManager.WsEvent.LatestMessageResolved -> event.message
                        }
                        LocalCache.cacheMessages(accountId, listOf(message))
                        LocalCache.applyNewMessageToConversation(
                            accountId = accountId,
                            message = message,
                            incrementUnread = false
                        )
                        ActiveConversationRegistry.activeKeyFor(message)
                            ?.let(ActiveConversationRegistry::requestRead)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun scheduleReadReceipt(scope: CoroutineScope, conversation: ConversationKey) {
        val job = scope.launch {
            delay(READ_RECEIPT_DEBOUNCE_MS.milliseconds)
            runCatching {
                dismissConversationNotification(conversation.chatId)
            }.onFailure { error ->
                Log.w(TAG, "Failed to mark ${conversation.chatId} as read", error)
            }
        }
        readReceiptJobs.put(conversation, job)?.cancel()
        job.invokeOnCompletion {
            readReceiptJobs.remove(conversation, job)
        }
    }
}
