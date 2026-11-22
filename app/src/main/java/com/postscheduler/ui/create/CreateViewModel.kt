package com.postscheduler.ui.create

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.postscheduler.data.PlannerRepository
import com.postscheduler.data.local.PostType
import com.postscheduler.data.remote.PageDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

class CreateViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PlannerRepository(app)

    // A picked photo/video and its background-upload state. The file is
    // uploaded to temp staging the moment it is picked, so saving never
    // waits on an upload; tempId is what the post links to on save.
    enum class UploadState { UPLOADING, DONE, FAILED }
    data class PickedMedia(
        val uri: Uri,
        val state: UploadState,
        val tempId: String? = null,
        val isVideo: Boolean = false
    )

    private val _media = MutableStateFlow<List<PickedMedia>>(emptyList())
    val media: StateFlow<List<PickedMedia>> = _media.asStateFlow()
    val hasPendingUploads: StateFlow<Boolean> =
        _media.map { list -> list.any { it.state == UploadState.UPLOADING } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun isVideoUri(uri: Uri): Boolean =
        getApplication<Application>().contentResolver.getType(uri)?.startsWith("video/") == true

    // Upload each picked file to temp staging in the background. A failed
    // upload stays on the tile with a retry instead of failing silently.
    fun addMedia(uris: List<Uri>) {
        val fresh = uris.filter { uri -> _media.value.none { it.uri == uri } }
        if (fresh.isEmpty()) return
        _dirty.value = true
        _media.value = _media.value + fresh.map { PickedMedia(it, UploadState.UPLOADING, isVideo = isVideoUri(it)) }
        for (uri in fresh) {
            viewModelScope.launch {
                runCatching { repo.uploadTempMedia(uri) }
                    .onSuccess { dto ->
                        _media.value = _media.value.map {
                            if (it.uri == uri) it.copy(state = UploadState.DONE, tempId = dto.id) else it
                        }
                    }
                    .onFailure {
                        _media.value = _media.value.map {
                            if (it.uri == uri) it.copy(state = UploadState.FAILED) else it
                        }
                    }
            }
        }
    }

    fun retryUpload(picked: PickedMedia) {
        _media.value = _media.value.map {
            if (it.uri == picked.uri) it.copy(state = UploadState.UPLOADING, tempId = null) else it
        }
        viewModelScope.launch {
            runCatching { repo.uploadTempMedia(picked.uri) }
                .onSuccess { dto ->
                    _media.value = _media.value.map {
                        if (it.uri == picked.uri) it.copy(state = UploadState.DONE, tempId = dto.id) else it
                    }
                }
                .onFailure {
                    _media.value = _media.value.map {
                        if (it.uri == picked.uri) it.copy(state = UploadState.FAILED) else it
                    }
                }
        }
    }

    // Remove a picked photo. If it already reached temp staging, delete it
    // from the server too so no orphan is left behind. Best-effort: the
    // tile is gone either way.
    fun removeMedia(picked: PickedMedia) {
        _media.value = _media.value.filter { it.uri != picked.uri }
        _dirty.value = true
        picked.tempId?.let { id ->
            viewModelScope.launch { repo.deleteTempMedia(id) }
        }
    }

    // Replace the current picks with a single file (story/reel). The old
    // staged file is deleted from the server.
    fun replaceMedia(uri: Uri) {
        val old = _media.value
        _media.value = emptyList()
        for (picked in old) {
            picked.tempId?.let { id ->
                viewModelScope.launch { repo.deleteTempMedia(id) }
            }
        }
        addMedia(listOf(uri))
    }

    fun clearMedia() {
        val old = _media.value
        _media.value = emptyList()
        for (picked in old) {
            picked.tempId?.let { id ->
                viewModelScope.launch { repo.deleteTempMedia(id) }
            }
        }
    }

    // After a successful save the staged files are claimed into the post,
    // so just drop the local list — do not delete them from the server.
    private fun resetMediaAfterSave() {
        _media.value = emptyList()
    }

    val pageName: StateFlow<String?> =
        repo.selectedPageName.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedPageId: StateFlow<String?> =
        repo.selectedPageId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The Pages the backend knows about, for the Change dropdown. Refreshed
    // whenever the selected Page changes and whenever the dropdown opens,
    // so a Page connected from the Page screen shows up right away.
    private val _pages = MutableStateFlow<List<PageDto>>(emptyList())
    val pages: StateFlow<List<PageDto>> = _pages.asStateFlow()

    fun refreshPages() = viewModelScope.launch {
        _pages.value = runCatching { repo.pages() }.getOrDefault(emptyList())
    }

    // Switch the selected Page (local choice only — the Page's token is
    // already on the backend from when it was connected). The picture and
    // name follow automatically via the selectedPageId collector below.
    fun selectPage(page: PageDto) = viewModelScope.launch {
        repo.setPage(page.id, page.name)
    }

    // The selected Page's profile picture URL, from the backend pages list.
    // Refreshed whenever the selected Page changes, so the Create tab's
    // avatar follows a page switch (the backend fills in a missing picture
    // automatically, so even pages connected with an older build get one).
    private val _pagePictureUrl = MutableStateFlow<String?>(null)
    val pagePictureUrl: StateFlow<String?> = _pagePictureUrl.asStateFlow()

    init {
        viewModelScope.launch {
            repo.selectedPageId.collect { id ->
                val list = runCatching { repo.pages() }.getOrDefault(emptyList())
                _pages.value = list
                _pagePictureUrl.value = list.firstOrNull { it.id == id }?.pictureUrl
            }
        }
    }

    private val _type = MutableStateFlow(PostType.POST)
    val type: StateFlow<PostType> = _type.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun setType(t: PostType) { _type.value = t; _dirty.value = true }

    // The composer's text, schedule and draft flag live here (not in the
    // screen) so the activity can ask "are there unsaved changes?" and
    // trigger a save from the tab-switch dialog. The screen reads them
    // and writes them back through the setters, which mark the composer
    // dirty. Loading a post for edit sets them directly (no dirty mark)
    // and clears the flag — the loaded post is the baseline.
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()
    private val _schedule = MutableStateFlow<Long?>(defaultScheduleMillis())
    val schedule: StateFlow<Long?> = _schedule.asStateFlow()
    private val _saveAsDraft = MutableStateFlow(false)
    val saveAsDraft: StateFlow<Boolean> = _saveAsDraft.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    fun setCaption(text: String) { _caption.value = text; _dirty.value = true }
    fun setSchedule(ms: Long?) { _schedule.value = ms; _dirty.value = true }
    fun setSaveAsDraft(v: Boolean) { _saveAsDraft.value = v; _dirty.value = true }

    // Back to a fresh composer (Discard / Cancel / after a save). Staged
    // temp files are deleted from the server; existing media is just
    // forgotten locally — the backend only deletes files on a save. The
    // schedule resets to the next hour, the same default a fresh composer
    // has always had.
    fun resetComposer() {
        clearMedia()
        editingPost = null
        _keptExisting.value = emptyList()
        _caption.value = ""
        _schedule.value = defaultScheduleMillis()
        _saveAsDraft.value = false
        _dirty.value = false
    }

    // The post being edited, if any — set by loadForEdit. When set, save()
    // PATCHes this post instead of creating a new one.
    private var editingPost: com.postscheduler.data.local.Post? = null
    val editing: Boolean get() = editingPost != null

    // Photos already on the post being edited that are still kept. The
    // editor shows them as removable tiles; removing one drops it from
    // this list, and save() sends the survivors as keepMedia so the
    // backend deletes the removed files.
    private val _keptExisting = MutableStateFlow<List<String>>(emptyList())
    val keptExisting: StateFlow<List<String>> = _keptExisting.asStateFlow()

    fun removeExistingMedia(uri: String) {
        _keptExisting.value = _keptExisting.value.filter { it != uri }
        _dirty.value = true
    }

    fun loadForEdit(post: com.postscheduler.data.local.Post) {
        clearMedia()
        editingPost = post
        _keptExisting.value = post.mediaList
        _type.value = post.type
        _caption.value = post.caption
        _schedule.value = post.scheduledAtMillis
        _saveAsDraft.value = false
        _dirty.value = false
    }

    fun clearEdit() {
        clearMedia()
        editingPost = null
        _keptExisting.value = emptyList()
        _dirty.value = false
    }

    /** Next hourly slot today, defaulting to the next hour (min 2 min ahead is enforced by the backend). */
    fun defaultScheduleMillis(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        return cal.timeInMillis
    }

    fun save(publishNow: Boolean) = viewModelScope.launch {
        // Caption, schedule and draft flag live in this ViewModel (not the
        // screen) so the tab-switch dialog can save without the screen.
        // Stories carry no caption even if text was typed before switching
        // type, so nothing stale is saved with a story.
        val caption = if (_type.value == PostType.STORY) "" else _caption.value
        val scheduledAtMillis = _schedule.value
        val saveAsDraft = _saveAsDraft.value
        // Wait for any background upload still in flight — a post cannot
        // link to a file that is not on the server yet. The save button
        // is disabled while uploads run, so this is a safety net.
        val settled = withTimeoutOrNull(60_000) {
            media.first { list -> list.none { it.state == UploadState.UPLOADING } }
        }
        if (settled == null) {
            _error.value = "Still uploading — try again in a moment."
            return@launch
        }
        val picked = _media.value
        val mediaUris = picked.map { it.uri }
        val tempMediaIds = picked.filter { it.state == UploadState.DONE }
            .mapNotNull { it.tempId }
        // Effective media: newly picked files if any, otherwise the photos
        // already on the post being edited. Counting only new picks wrongly
        // blocked editing a story that already has its one photo or video
        // ("Add text or media first" / "A story needs exactly one photo
        // or video"), and let a multi-photo post slip past the story check
        // when no new file was picked. The kept list shrinks when the user
        // removes a photo in the editor, so the count follows the removal.
        val effectiveMediaCount = if (mediaUris.isNotEmpty()) mediaUris.size
            else _keptExisting.value.size
        if (caption.isBlank() && effectiveMediaCount == 0) {
            _error.value = "Add text or media first."
            return@launch
        }
        if (_type.value == PostType.REEL && effectiveMediaCount != 1) {
            _error.value = "A reel needs exactly one video (MP4)."
            return@launch
        }
        if (_type.value == PostType.STORY && effectiveMediaCount != 1) {
            _error.value = "A story needs exactly one photo or video."
            return@launch
        }
        // A picked file whose upload failed cannot be saved — it is not on
        // the server. The tile shows a retry.
        if (picked.any { it.state == UploadState.FAILED }) {
            _error.value = "One photo failed to upload — retry it or remove it."
            return@launch
        }
        _saving.value = true
        runCatching {
            val editing = editingPost
            if (editing != null) {
                // No new picks: keepMedia lists the surviving photos (by
                // URL basename) so the backend deletes the removed ones.
                // With new picks the old photos are all replaced instead.
                val keepMedia = if (tempMediaIds.isEmpty()) {
                    _keptExisting.value.map { it.substringAfterLast("/") }.filter { it.isNotBlank() }
                } else null
                val updated = repo.updatePost(
                    editing, _type.value, caption, mediaUris, tempMediaIds, scheduledAtMillis, saveAsDraft,
                    keptExistingMedia = if (tempMediaIds.isEmpty()) _keptExisting.value else emptyList(),
                    keepMedia = keepMedia
                )
                if (publishNow) repo.publishNow(updated)
                editingPost = null
                _keptExisting.value = emptyList()
            } else {
                repo.createPost(_type.value, caption, mediaUris, tempMediaIds, scheduledAtMillis, publishNow, saveAsDraft)
            }
        }.onSuccess {
            resetMediaAfterSave()
            resetComposer()
            _done.value = true
        }
            .onFailure { _error.value = "Saved locally, backend sync failed: ${it.message}" }
        _saving.value = false
    }

    fun clearError() { _error.value = null }
    fun resetDone() { _done.value = false }
}
