package com.postscheduler.ui.publish

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postscheduler.data.PlannerRepository
import com.postscheduler.data.local.Post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PublishViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PlannerRepository(app)
    val queue = repo.queue()
    val drafts = repo.drafts()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { _paused.value = repo.queuePaused() }
            runCatching { repo.refreshQueue() }.onFailure { _error.value = it.message }
        }
    }

    /** Re-pull the queue from the backend. Called when the Publish tab is
     * shown, so posts scheduled on the website (or after this ViewModel was
     * first created) appear — init only runs once, and the ViewModel is
     * kept across tab switches, so init alone never re-syncs. */
    fun refresh() = viewModelScope.launch {
        runCatching { _paused.value = repo.queuePaused() }
        runCatching { repo.refreshQueue() }.onFailure { _error.value = it.message }
    }

    fun togglePause() = viewModelScope.launch {
        val target = !_paused.value
        runCatching { repo.setQueuePaused(target) }
            .onSuccess { _paused.value = target }
            .onFailure { _error.value = it.message }
    }

    fun publishNow(post: Post) = viewModelScope.launch {
        runCatching { repo.publishNow(post) }.onFailure { _error.value = it.message }
    }

    fun updateSchedule(post: Post, scheduledAtMillis: Long) = viewModelScope.launch {
        runCatching { repo.updateSchedule(post, scheduledAtMillis) }
            .onFailure { _error.value = it.message }
    }

    fun delete(post: Post) = viewModelScope.launch {
        runCatching { repo.deletePost(post.id) }.onFailure { _error.value = it.message }
    }
    fun clearError() { _error.value = null }
}
