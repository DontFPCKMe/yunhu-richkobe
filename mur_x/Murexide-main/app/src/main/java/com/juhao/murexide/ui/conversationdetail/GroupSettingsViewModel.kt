package com.juhao.murexide.ui.conversationdetail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juhao.murexide.repository.ConversationDetailRepository
import com.juhao.murexide.utils.QiniuUploader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupSettingsUiState(
    val isLoading: Boolean = true,
    val name: String = "",
    val introduction: String = "",
    val avatarUrl: String = "",
    val directJoin: Boolean = false,
    val historyMsg: Boolean = false,
    val isPrivate: Boolean = false,
    val hideGroupMembers: Boolean = false,
    val myGroupNickname: String = "",
    val categoryName: String = "",
    val categoryId: Long = 0L,
    val isUploadingAvatar: Boolean = false,
    val uploadProgress: Float = 0f,
    val isSaving: Boolean = false
)

class GroupSettingsViewModel(
    private val token: String,
    private val groupId: String,
    fallbackName: String = "",
    fallbackAvatar: String = "",
    private val repository: ConversationDetailRepository = ConversationDetailRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        GroupSettingsUiState(name = fallbackName, avatarUrl = fallbackAvatar)
    )
    val uiState: StateFlow<GroupSettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GroupSettingsEvent>()
    val events: SharedFlow<GroupSettingsEvent> = _events.asSharedFlow()

    sealed class GroupSettingsEvent {
        data class ShowToast(val message: String) : GroupSettingsEvent()
        object Saved : GroupSettingsEvent()
    }

    init {
        loadDetail()
    }

    private fun loadDetail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getDetail(token, groupId, 2)
                .onSuccess { d ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            name = d.name,
                            introduction = d.introduction,
                            avatarUrl = d.avatarUrl,
                            directJoin = d.directJoin,
                            historyMsg = d.historyMsg,
                            isPrivate = d.isPrivate,
                            hideGroupMembers = d.hideGroupMembers,
                            myGroupNickname = d.myGroupNickname ?: "",
                            categoryName = d.categoryName ?: "",
                            categoryId = d.categoryId ?: 0L
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false) }
                    _events.emit(GroupSettingsEvent.ShowToast(e.message ?: "加载群信息失败"))
                }
        }
    }

    fun updateName(value: String) = _uiState.update { it.copy(name = value) }
    fun updateIntroduction(value: String) = _uiState.update { it.copy(introduction = value) }
    fun toggleDirectJoin(value: Boolean) = _uiState.update { it.copy(directJoin = value) }
    fun toggleHistoryMsg(value: Boolean) = _uiState.update { it.copy(historyMsg = value) }
    fun togglePrivate(value: Boolean) = _uiState.update { it.copy(isPrivate = value) }
    fun toggleHideMembers(value: Boolean) = _uiState.update { it.copy(hideGroupMembers = value) }

    fun uploadAvatar(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAvatar = true, uploadProgress = 0f) }
            val uploader = QiniuUploader(context = context, userToken = token, enableWebp = true)
            uploader.uploadFromUri(
                context = context,
                uri = uri,
                onProgress = { percent ->
                    _uiState.update { it.copy(uploadProgress = percent) }
                }
            ).onSuccess { response ->
                val avatarUrl = "https://chat-img.jwznb.com/${response.key}"
                _uiState.update { it.copy(isUploadingAvatar = false, avatarUrl = avatarUrl) }
                _events.emit(GroupSettingsEvent.ShowToast("头像已上传，保存后生效"))
            }.onFailure { e ->
                _uiState.update { it.copy(isUploadingAvatar = false) }
                _events.emit(GroupSettingsEvent.ShowToast("头像上传失败: ${e.message}"))
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.isUploadingAvatar) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            val result = repository.editGroup(
                token = token,
                groupId = groupId,
                name = state.name,
                introduction = state.introduction,
                avatarUrl = state.avatarUrl,
                directJoin = state.directJoin,
                historyMsg = state.historyMsg,
                isPrivate = state.isPrivate,
                hideGroupMembers = state.hideGroupMembers,
                categoryName = state.categoryName,
                categoryId = state.categoryId
            )

            result.onSuccess {
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(GroupSettingsEvent.ShowToast("群设置已保存"))
                _events.emit(GroupSettingsEvent.Saved)
            }.onFailure { e ->
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(GroupSettingsEvent.ShowToast(e.message ?: "保存失败"))
            }
        }
    }
}
