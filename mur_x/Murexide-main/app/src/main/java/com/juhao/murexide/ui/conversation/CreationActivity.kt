package com.juhao.murexide.ui.conversation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juhao.murexide.datastore.AccountStorage
import com.juhao.murexide.ui.components.Avatar
import com.juhao.murexide.ui.components.StyledSwitch
import com.juhao.murexide.ui.conversationdetail.ConversationDetailActivity
import com.juhao.murexide.ui.icons.AppIcons
import com.juhao.murexide.ui.icons.AutoMirroredIcon
import com.juhao.murexide.ui.theme.MurexideTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CreationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { CreationKind.valueOf(it) }.getOrNull() }
            ?: return finish()
        val tokenState = mutableStateOf<String?>(null)
        setContent {
            MurexideTheme {
                val token = tokenState.value
                if (token == null) LoadingScreen() else CreationScreen(
                    kind = kind,
                    onBack = ::finish,
                    onCreated = { chat ->
                        ConversationDetailActivity.start(this, chat.chatId, chat.chatType, chat.name, chat.avatarUrl)
                        finish()
                    },
                    viewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>) = CreationViewModel(token, kind) as T
                    })
                )
            }
        }
        lifecycleScope.launch {
            tokenState.value = AccountStorage.getInstance(this@CreationActivity).getCurrentToken()
                ?: run {
                    Toast.makeText(this@CreationActivity, "请先登录", Toast.LENGTH_SHORT).show()
                    finish()
                    ""
                }
        }
    }

    companion object {
        private const val EXTRA_KIND = "creation_kind"
        fun start(context: Context, kind: CreationKind) {
            context.startActivity(Intent(context, CreationActivity::class.java).putExtra(EXTRA_KIND, kind.name))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationScreen(
    kind: CreationKind,
    onBack: () -> Unit,
    onCreated: (com.juhao.murexide.data.CreatedChat) -> Unit,
    viewModel: CreationViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.uploadAvatar(context, uri)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is CreationEvent.Created -> onCreated(event.chat)
                is CreationEvent.ShowMessage -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = { IconButton(onClick = onBack) { AutoMirroredIcon(AppIcons.ArrowBack, "返回") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::create,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp),
                content = {
                    if (state.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(AppIcons.Check, contentDescription = kind.title)
                    }
                }
            )
        }
    ) { padding ->
        val introductionScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.BottomEnd,
                            modifier = Modifier.clickable(enabled = !state.isUploadingAvatar && !state.isCreating) {
                                imagePicker.launch("image/*")
                            }
                        ) {
                            CreationAvatar(
                                url = state.avatarUrl,
                                name = state.name,
                                kind = kind,
                                size = 72.dp
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(AppIcons.Edit, contentDescription = "选择头像", modifier = Modifier.padding(5.dp))
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = state.name,
                                onValueChange = viewModel::updateName,
                                singleLine = true,
                                enabled = !state.isCreating,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (state.name.isBlank()) {
                                            Text(
                                                text = if (kind == CreationKind.GROUP) "群聊名称" else "机器人名称",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (state.isUploadingAvatar) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { state.uploadProgress }, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(20.dp))
                    BasicTextField(
                        value = state.introduction,
                        onValueChange = viewModel::updateIntroduction,
                        enabled = !state.isCreating,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(introductionScrollState)
                            .heightIn(min = 96.dp, max = 180.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (state.introduction.isBlank()) {
                                    Text(
                                        text = "介绍（可选）",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
            if (kind == CreationKind.BOT) {
                Spacer(Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("私有机器人")
                        Text("仅允许被添加后使用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StyledSwitch(
                        checked = state.isPrivate,
                        onCheckedChange = viewModel::updatePrivate,
                        enabled = !state.isCreating,
                    )
                }
            }
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun CreationAvatar(
    url: String,
    name: String,
    kind: CreationKind,
    size: androidx.compose.ui.unit.Dp
) {
    if (url.isNotBlank()) {
        Avatar(url = url, size = size)
        return
    }

    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        val initial = name.trim().firstOrNull()?.toString()
        Box(contentAlignment = Alignment.Center) {
            if (initial == null) {
                Icon(
                    imageVector = if (kind == CreationKind.GROUP) AppIcons.Group else AppIcons.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(size / 2)
                )
            } else {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}
