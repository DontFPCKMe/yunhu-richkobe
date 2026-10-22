package com.juhao.murexide.ui.community.ba

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juhao.murexide.data.BaDetail
import com.juhao.murexide.data.PostItem
import com.juhao.murexide.datastore.AccountStorage
import com.juhao.murexide.repository.CommunityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BaDetailViewModel(
    private val accountStorage: AccountStorage,
    private val baId: Int
) : ViewModel() {

    private var repository: CommunityRepository? = null
    private val pageSize = 20

    private val _uiState = MutableStateFlow(BaDetailUiState())
    val uiState: StateFlow<BaDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val token = accountStorage.getCurrentToken()
            if (token == null) {
                _uiState.update { it.copy(error = "请先登录", isLoadingInfo = false, isLoadingPosts = false) }
                return@launch
            }
            repository = CommunityRepository(token)
            loadInfo()
            loadPosts(reset = true)
        }
    }

    fun loadInfo() {
        val repo = repository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingInfo = true) }
            repo.getBaInfo(baId)
                .onSuccess { ba -> _uiState.update { it.copy(ba = ba, isLoadingInfo = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoadingInfo = false, error = e.message) } }
        }
    }

    fun loadPosts(reset: Boolean) {
        val repo = repository ?: return
        val state = _uiState.value
        if (state.isLoadingPosts || state.isLoadingMore) return
        if (!reset && !state.hasMore) return

        val nextPage = if (reset) 1 else state.currentPage + 1
        viewModelScope.launch {
            _uiState.update {
                if (reset) it.copy(isLoadingPosts = true) else it.copy(isLoadingMore = true)
            }
            repo.getPostList(baId, size = pageSize, page = nextPage)
                .onSuccess { data ->
                    val merged = if (reset) data.posts else state.posts + data.posts
                    _uiState.update {
                        it.copy(
                            posts = merged,
                            total = data.total,
                            currentPage = nextPage,
                            hasMore = data.posts.isNotEmpty() && merged.size < data.total,
                            isLoadingPosts = false,
                            isLoadingMore = false,
                            isRefreshing = false
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoadingPosts = false, isLoadingMore = false, isRefreshing = false, error = e.message)
                    }
                }
        }
    }

    fun loadMore() = loadPosts(reset = false)

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadInfo()
        loadPosts(reset = true)
    }

    fun toggleFollow() {
        val repo = repository ?: return
        val ba = _uiState.value.ba ?: return
        val isFollowed = ba.isFollowed == "1"
        viewModelScope.launch {
            val result = if (isFollowed) repo.unfollowBa(baId) else repo.followBa(baId)
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        ba = ba.copy(
                            isFollowed = if (isFollowed) "0" else "1",
                            memberNum = if (isFollowed) (ba.memberNum - 1).coerceAtLeast(0) else ba.memberNum + 1
                        )
                    )
                }
            }
        }
    }

    fun toggleLike(postId: Int) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.toggleLike(postId).onSuccess {
                _uiState.update { state ->
                    state.copy(posts = state.posts.map { post ->
                        if (post.id == postId) {
                            val liked = post.isLiked == "0"
                            post.copy(
                                isLiked = if (liked) "1" else "0",
                                likeNum = if (liked) post.likeNum + 1 else (post.likeNum - 1).coerceAtLeast(0)
                            )
                        } else post
                    })
                }
            }
        }
    }

    fun toggleCollect(postId: Int) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.toggleCollect(postId).onSuccess {
                _uiState.update { state ->
                    state.copy(posts = state.posts.map { post ->
                        if (post.id == postId) {
                            val collected = post.isCollected == 0
                            post.copy(
                                isCollected = if (collected) 1 else 0,
                                collectNum = if (collected) post.collectNum + 1 else (post.collectNum - 1).coerceAtLeast(0)
                            )
                        } else post
                    })
                }
            }
        }
    }
}

data class BaDetailUiState(
    val ba: BaDetail? = null,
    val posts: List<PostItem> = emptyList(),
    val isLoadingInfo: Boolean = true,
    val isLoadingPosts: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val currentPage: Int = 0,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null
)
