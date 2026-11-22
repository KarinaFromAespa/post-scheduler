package com.postscheduler.ui.drafts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postscheduler.data.PlannerRepository
import com.postscheduler.data.local.Post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DraftsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PlannerRepository(app)

    val drafts: Flow<List<Post>> = repo.drafts()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun delete(post: Post) = viewModelScope.launch {
        runCatching { repo.deletePost(post.id) }.onFailure { _error.value = it.message }
    }

    fun clearError() { _error.value = null }

    /** Publish a draft right now, bypassing the queue. */
    fun publishNow(post: Post) = viewModelScope.launch {
        runCatching { repo.publishNow(post) }
    }
}
