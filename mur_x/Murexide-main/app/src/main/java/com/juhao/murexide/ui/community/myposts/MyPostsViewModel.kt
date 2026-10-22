package com.juhao.murexide.ui.community.myposts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juhao.murexide.data.PostItem
import com.juhao.murexide.datastore.AccountStorage
import com.juhao.murexide.repository.CommunityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MyPostsViewModel(
    private val accountStorage: AccountStorage
) : ViewModel() {

    private var repository: CommunityRepository? = null
    private val pageSize = 20

    private val _uiState = MutableStateFlow(MyPostsUiState())
    val uiState: StateFlow<MyPostsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val token = accountStorage.getCurrentToken()
            if (token == null) {
                _uiState.update { it.copy(isLoading = false, error = "请先登录") }
                return@launch
            }
            repository = CommunityRepository(token)
            loadPosts(reset = true)
        }
    }

    fun loadPosts(reset: Boolean) {
        val repo = repository ?: return
        val state = _uiState.value
        if (state.isLoadingMore) return
        if (!reset && !state.hasMore) return

        val nextPage = if (reset) 1 else state.currentPage + 1
        viewModelScope.launch {
            _uiState.update {
                if (reset) it.copy(isLoading = it.posts.isEmpty(), isRefreshing = it.posts.isNotEmpty())
                else it.copy(isLoadingMore = true)
            }
            repo.getMyPostList(size = pageSize, page = nextPage)
                .onSuccess { data ->
                    val merged = if (reset) data.posts else state.posts + data.posts
                    _uiState.update {
                        it.copy(
                            posts = merged,
                            total = data.total,
                            currentPage = nextPage,
                            hasMore = data.posts.isNotEmpty() && merged.size < data.total,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = e.message)
                    }
                }
        }
    }

    fun loadMore() = loadPosts(reset = false)

    fun refresh() = loadPosts(reset = true)
}

data class MyPostsUiState(
    val posts: List<PostItem> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val currentPage: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null
)
