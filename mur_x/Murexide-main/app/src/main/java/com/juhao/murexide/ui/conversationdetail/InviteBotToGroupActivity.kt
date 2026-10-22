package com.juhao.murexide.ui.conversationdetail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juhao.murexide.data.ForwardTarget
import com.juhao.murexide.data.filterForwardTargets
import com.juhao.murexide.data.mergeForwardTargets
import com.juhao.murexide.datastore.AccountStorage
import com.juhao.murexide.repository.ConversationRepository
import com.juhao.murexide.repository.FriendRepository
import com.juhao.murexide.ui.components.Avatar
import com.juhao.murexide.ui.icons.AppIcons
import com.juhao.murexide.ui.icons.AutoMirroredIcon
import com.juhao.murexide.ui.theme.MurexideTheme
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

private data class InviteBotToGroupUiState(
    val isLoading: Boolean = true,
    val groups: List<ForwardTarget> = emptyList(),
    val query: String = "",
    val selectedIds: Set<String> = emptySet(),
    val isInviting: Boolean = false,
    val error: String? = null
) {
    val filteredGroups: List<ForwardTarget>
        get() = groups.filterForwardTargets(query)

    val selectedGroups: List<ForwardTarget>
        get() = groups.filter { it.chatId in selectedIds }
}

private class InviteBotToGroupViewModel(
    private val token: String,
    private val botId: String,
    private val conversationRepository: ConversationRepository = ConversationRepository(),
    private val friendRepository: FriendRepository = FriendRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(InviteBotToGroupUiState())
    val uiState: StateFlow<InviteBotToGroupUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        loadGroups()
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query, error = null) }
    }

    fun toggleGroup(group: ForwardTarget) {
        _uiState.update { state ->
            if (state.isInviting) return@update state
            state.copy(
                selectedIds = if (group.chatId in state.selectedIds) {
                    state.selectedIds - group.chatId
                } else {
                    state.selectedIds + group.chatId
                },
                error = null
            )
        }
    }

    fun retry() {
        if (!_uiState.value.isLoading && !_uiState.value.isInviting) loadGroups()
    }

    fun invite() {
        val groups = _uiState.value.selectedGroups
        if (groups.isEmpty() || _uiState.value.isInviting) return
        _uiState.update { it.copy(isInviting = true, error = null) }
        viewModelScope.launch {
            for ((succeeded, group) in groups.withIndex()) {
                val result = friendRepository.inviteBotToGroup(token, botId, group.chatId)
                if (result.isFailure) {
                    val reason = result.exceptionOrNull()?.message ?: "邀请失败"
                    _uiState.update {
                        it.copy(
                            isInviting = false,
                            error = if (succeeded == 0) reason else "已邀请 $succeeded/${groups.size} 个群聊，$reason"
                        )
                    }
                    return@launch
                }
            }
            _uiState.update { it.copy(isInviting = false) }
            _events.emit("已加入群聊")
        }
    }

    private fun loadGroups() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val cachedConversations = UiCache.conversation.value
            val cachedSticky = UiCache.stickyConversations.value
            val targets = withContext(Dispatchers.IO) {
                val conversations = cachedConversations.ifEmpty {
                    conversationRepository.getConversationList(token).getOrNull().orEmpty()
                }
                val sticky = cachedSticky.ifEmpty {
                    conversationRepository.getStickyList(token).getOrNull().orEmpty()
                }
                mergeForwardTargets(conversations, sticky).filter { it.chatType == 2 }
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    groups = targets,
                    error = if (targets.isEmpty()) "暂无可邀请的群聊" else null
                )
            }
        }
    }
}

class InviteBotToGroupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val botId = intent.getStringExtra(EXTRA_BOT_ID).orEmpty()
        if (botId.isBlank()) {
            Toast.makeText(this, "无效的机器人", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val botName = intent.getStringExtra(EXTRA_BOT_NAME).orEmpty()
        val tokenState = MutableStateFlow<String?>(null)
        val accountStorage = AccountStorage.getInstance(this)

        setContent {
            MurexideTheme {
                val token by tokenState.collectAsStateWithLifecycle()
                val currentToken = token
                if (currentToken == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    InviteBotToGroupScreen(
                        botName = botName,
                        onBack = ::finish,
                        onDone = { message ->
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        viewModel = viewModel(
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                    InviteBotToGroupViewModel(currentToken, botId) as T
                            }
                        )
                    )
                }
            }
        }

        lifecycleScope.launch {
            val token = runCatching { accountStorage.getCurrentToken() }.getOrNull()
            if (token == null) {
                Toast.makeText(this@InviteBotToGroupActivity, "请先登录", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                tokenState.value = token
            }
        }
    }

    companion object {
        private const val EXTRA_BOT_ID = "bot_id"
        private const val EXTRA_BOT_NAME = "bot_name"

        fun start(context: Context, botId: String, botName: String) {
            context.startActivity(Intent(context, InviteBotToGroupActivity::class.java).apply {
                putExtra(EXTRA_BOT_ID, botId)
                putExtra(EXTRA_BOT_NAME, botName)
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteBotToGroupScreen(
    botName: String,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
    viewModel: InviteBotToGroupViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.events.collect(onDone)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("添加到群")
                        if (botName.isNotBlank()) {
                            Text(
                                botName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(enabled = !state.isInviting, onClick = onBack) {
                        AutoMirroredIcon(AppIcons.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    enabled = !state.isInviting,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    placeholder = { Text("搜索群聊") },
                    shape = RoundedCornerShape(18.dp)
                )
                state.error?.let { error ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        if (state.groups.isEmpty() && !state.isInviting) {
                            androidx.compose.material3.TextButton(onClick = viewModel::retry) { Text("重试") }
                        }
                    }
                }
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.filteredGroups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (state.query.isBlank()) "暂无可邀请的群聊" else "未找到相关群聊", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 92.dp)
                    ) {
                        items(state.filteredGroups, key = ForwardTarget::chatId) { group ->
                            InviteGroupRow(
                                group = group,
                                selected = group.chatId in state.selectedIds,
                                enabled = !state.isInviting,
                                onClick = { viewModel.toggleGroup(group) }
                            )
                        }
                    }
                }
            }
            if (state.selectedIds.isNotEmpty()) {
                FloatingActionButton(
                    onClick = viewModel::invite,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp).navigationBarsPadding(),
                    shape = CircleShape
                ) {
                    if (state.isInviting) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(AppIcons.Group, "邀请至所选群聊")
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteGroupRow(group: ForwardTarget, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Avatar(url = group.avatarUrl, size = 52.dp)
        Text(
            group.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (selected) Icon(AppIcons.Check, "已选择", Modifier.size(15.dp))
            }
        }
    }
}
