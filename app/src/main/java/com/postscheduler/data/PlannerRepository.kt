package com.postscheduler.data

import android.content.Context
import android.net.Uri
import com.postscheduler.data.local.AppDatabase
import com.postscheduler.data.local.InsightEntity
import com.postscheduler.data.local.Post
import com.postscheduler.data.local.PostStatus
import com.postscheduler.data.local.PostType
import com.postscheduler.data.remote.ApiFactory
import com.postscheduler.data.remote.CreatePostRequest
import com.postscheduler.data.remote.InsightsDto
import com.postscheduler.data.remote.PlannerApi
import com.postscheduler.data.remote.toApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Local-first repository. The queue/drafts live in Room so the Today list
 * works offline; every create / publish-now / pause also goes to the backend,
 * which owns the 1-minute publisher and Object Storage.
 * If the backend is unreachable, the local copy is kept and the error is
 * surfaced to the UI — nothing is silently dropped.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlannerRepository(context: Context) {
    private val db = AppDatabase.get(context.applicationContext)
    private val dao = db.postDao()
    private val insightDao = db.insightDao()
    private val settings = com.postscheduler.data.remote.SettingsStore(context.applicationContext)
    private val appContext = context.applicationContext

    // "video" when the picked file is a video (a reel, or a video story),
    // "photo" when it is a photo, "" when there is no media. Read from
    // the file's MIME type — the post type alone cannot tell a photo
    // story from a video one.
    private fun mediaKindFor(type: PostType, mediaUris: List<Uri>): String {
        if (type == PostType.REEL) return "video"
        if (mediaUris.isEmpty()) return ""
        val mime = mediaUris.firstOrNull()?.let { appContext.contentResolver.getType(it) } ?: ""
        return if (mime.startsWith("video/")) "video" else "photo"
    }

    // Queue/drafts/published follow the selected Page: switching Pages
    // re-runs the query for that Page only, so one Page never sees
    // another Page's posts. flatMapLatest re-queries on every Page change.
    fun queue(): Flow<List<Post>> = settings.pageId.flatMapLatest { pageId -> dao.queue(pageId) }
    fun drafts(): Flow<List<Post>> = settings.pageId.flatMapLatest { pageId -> dao.drafts(pageId) }
    fun published(): Flow<List<Post>> = settings.pageId.flatMapLatest { pageId -> dao.published(pageId) }
    fun insights(): Flow<List<InsightEntity>> = insightDao.all()

    private fun api(): PlannerApi = ApiFactory.create(settings.currentBackendUrl())

    val selectedPageId: Flow<String?> = settings.pageId
    val selectedPageName: Flow<String?> = settings.pageName
    fun currentPageId(): String? = settings.currentPageId()
    fun currentPageName(): String? = settings.currentPageName()
    suspend fun setPage(id: String?, name: String?) = settings.setPage(id, name)

    /** Pages the backend knows about (from the Page token it holds). Empty when the backend is unreachable or has no Page configured yet. */
    suspend fun pages(): List<com.postscheduler.data.remote.PageDto> =
        runCatching { api().pages() }.getOrDefault(emptyList())

    /**
     * Hands a Facebook-Login Page token to the backend so it can publish as
     * that Page. Always saves the choice locally; returns true only if the
     * backend accepted the token (its POST /api/pages/connect may not exist
     * yet — then posts save locally and the token stays on the phone).
     */
    suspend fun connectPage(id: String, name: String, accessToken: String, pictureUrl: String? = null): Boolean {
        val stored = runCatching {
            api().connectPage(com.postscheduler.data.remote.ConnectPageRequest(id, name, accessToken, pictureUrl))
        }.isSuccess
        setPage(id, name)
        return stored
    }

    /** Remove a Page from the backend (and clear it locally). The backend
     * also moves that Page's scheduled posts to draft so they leave the
     * queue instead of sitting there as scheduled forever. Best-effort:
     * the local page is cleared even if the backend is unreachable. */
    /** Remove one Page from the backend only, without touching the local
     * selection. Used to delete a Page listed under "Connected Pages"
     * that is not the currently selected one — clearing the local Page
     * there would wrongly deselect the Page the user is posting to. */
    suspend fun removePage(id: String): Boolean =
        runCatching { api().deletePage(id) }.isSuccess

    suspend fun setBackendUrl(url: String) = settings.setBackendUrl(url)
    fun backendUrl(): Flow<String> = settings.backendUrl

    suspend fun refreshQueue() {
        // Sync every post, not just the queue. /api/queue only returns
        // scheduled posts, so once the backend published one it dropped
        // out of the response and Room kept it SCHEDULED forever — it
        // stayed on the Publish tab as a ghost long after it went out.
        // /api/posts returns all statuses, so published posts become
        // PUBLISHED (and leave the queue) and failed ones become FAILED
        // (and keep their Retry). Fall back to /api/queue if the backend
        // does not have /api/posts yet.
        val dtos = runCatching { api().allPosts() }
            .getOrElse { runCatching { api().queue() }.getOrNull() } ?: return
        dtos.forEach { dto ->
            // A post created in the app first lives under a random local
            // id; the backend pull keys it by the backend id. Drop the
            // stale local row first, or the same post shows twice.
            dao.deleteDuplicatesForBackendId(dto.id)
            dao.upsert(dto.toLocal())
        }
    }

    // Stage a picked file on the server in the background (before the post
    // exists). Returns the temp id the post links to on save. The caller
    // tracks it; removing the photo calls deleteTempMedia so no orphan
    // is left on the server.
    suspend fun uploadTempMedia(uri: Uri): com.postscheduler.data.remote.TempMediaDto {
        // Send the file's real MIME type and keep its extension on the
        // cache filename — the backend stores both, and the app and the
        // website use them to tell a photo from a video (thumbnail, play
        // badge, publish path). Sending application/octet-stream for
        // everything made every file unreadable as a photo or a video.
        val mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = when {
            mimeType == "image/jpeg" -> ".jpg"
            mimeType == "image/png" -> ".png"
            mimeType == "image/gif" -> ".gif"
            mimeType == "image/webp" -> ".webp"
            mimeType == "video/mp4" -> ".mp4"
            mimeType == "video/quicktime" -> ".mov"
            mimeType == "video/webm" -> ".webm"
            else -> ""
        }
        val file = File(appContext.cacheDir, "upload-${UUID.randomUUID()}$ext")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return try {
            val body = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", file.name, body)
            api().uploadTempMedia(part)
        } finally {
            file.delete()
        }
    }

    suspend fun deleteTempMedia(tempId: String) {
        runCatching { api().deleteTempMedia(tempId) }
    }

    suspend fun createPost(
        type: PostType,
        caption: String,
        mediaUris: List<Uri>,
        tempMediaIds: List<String>,
        scheduledAtMillis: Long?,
        publishNow: Boolean,
        saveAsDraft: Boolean = false
    ): Post {
        val post = Post(
            id = UUID.randomUUID().toString(),
            type = type,
            caption = caption.trim(),
            hashtags = "",
            mediaUris = mediaUris.joinToString(",") { it.toString() },
            mediaKind = mediaKindFor(type, mediaUris),
            // A draft keeps its planned time (if one was picked) — it just
            // doesn't publish until it is scheduled.
            scheduledAtMillis = scheduledAtMillis,
            status = when {
                publishNow -> PostStatus.PUBLISHING
                saveAsDraft || scheduledAtMillis == null -> PostStatus.DRAFT
                else -> PostStatus.SCHEDULED
            },
            // Tag the local row with the Page now, so it lands in the right
            // Page's queue immediately instead of waiting for the next
            // backend sync to fill it in.
            pageId = currentPageId()
        )
        dao.upsert(post)
        // Best-effort backend sync; local copy stays even if this fails.
        runCatching {
            val dto = api().createPost(
                CreatePostRequest(
                    type = type.toApi(), caption = post.caption,
                    hashtags = null,
                    scheduledAt = scheduledAtMillis?.let { iso(it) },
                    status = post.status.toApi(), publishNow = publishNow,
                    pageId = currentPageId(),
                    tempMediaIds = tempMediaIds.ifEmpty { null }
                )
            )
            // Re-key the local row by the backend id (dropping the random
            // local-UUID row), so a later backend pull updates this same row
            // instead of adding a second copy of the post.
            dao.delete(post.id)
            dao.upsert(post.copy(id = dto.id, backendId = dto.id))
            if (publishNow) {
                runCatching { api().publishNow(dto.id) }
            }
        }.onFailure { e ->
            dao.upsert(post.copy(status = PostStatus.FAILED, failureReason = e.message))
            throw e
        }
        return post
    }

    /**
     * Edit a scheduled post or draft: change its time/date, caption or
     * photos. PATCHes the same backend post (so its place in the queue
     * and its id are kept) and updates the local copy. New photos replace
     * the old ones; no new photos means the old ones stay.
     */
    suspend fun updatePost(
        post: Post,
        type: PostType,
        caption: String,
        mediaUris: List<Uri>,
        tempMediaIds: List<String>,
        scheduledAtMillis: Long?,
        saveAsDraft: Boolean = false
    ): Post {
        val updated = post.copy(
            type = type,
            caption = caption.trim(),
            mediaUris = if (mediaUris.isNotEmpty()) mediaUris.joinToString(",") { it.toString() } else post.mediaUris,
            mediaKind = if (mediaUris.isNotEmpty()) mediaKindFor(type, mediaUris) else post.mediaKind,
            scheduledAtMillis = scheduledAtMillis,
            status = if (saveAsDraft || scheduledAtMillis == null) PostStatus.DRAFT else PostStatus.SCHEDULED,
            failureReason = null,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.upsert(updated)
        runCatching {
            val backendId = post.backendId ?: post.id
            api().updatePost(
                backendId,
                com.postscheduler.data.remote.UpdatePostRequest(
                    type = type.toApi(), caption = updated.caption,
                    scheduledAt = scheduledAtMillis?.let { iso(it) },
                    status = updated.status.toApi(), pageId = currentPageId(),
                    tempMediaIds = tempMediaIds.ifEmpty { null }
                )
            )
        }.onFailure { e ->
            dao.upsert(updated.copy(status = PostStatus.FAILED, failureReason = e.message))
            throw e
        }
        return updated
    }

    /** Change only the scheduled time of a scheduled post (the Publish
     * tab's "Update schedule" action). Type, caption and photos are left
     * alone. The backend rejects a time less than 2 minutes ahead,
     * so the caller must validate the time first. */
    suspend fun updateSchedule(post: Post, scheduledAtMillis: Long): Post {
        val updated = post.copy(
            scheduledAtMillis = scheduledAtMillis,
            status = PostStatus.SCHEDULED,
            failureReason = null,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.upsert(updated)
        runCatching {
            val backendId = post.backendId ?: post.id
            api().updatePost(
                backendId,
                com.postscheduler.data.remote.UpdatePostRequest(
                    scheduledAt = iso(scheduledAtMillis),
                    status = "scheduled"
                )
            )
        }.onFailure { e ->
            dao.upsert(updated.copy(status = PostStatus.FAILED, failureReason = e.message))
            throw e
        }
        return updated
    }

    suspend fun publishNow(post: Post) {
        dao.upsert(post.copy(status = PostStatus.PUBLISHING))
        runCatching {
            val backendId = post.backendId ?: error("Not synced to backend yet")
            api().publishNow(backendId)
            dao.upsert(post.copy(status = PostStatus.PUBLISHED))
        }.onFailure { e ->
            dao.upsert(post.copy(status = PostStatus.FAILED, failureReason = e.message))
            throw e
        }
    }

    // Delete from the backend first, then locally. The old version only
    // deleted the local row, so the next backend refresh (every time the
    // Publish tab is shown) put the post straight back — the delete
    // looked like it did nothing. A 404 means the backend never had it
    // (a local-only post), so deleting locally is still correct; any
    // other failure leaves the local row so the post is not lost.
    suspend fun deletePost(id: String) {
        runCatching { api().deletePost(id) }.onFailure { e ->
            if (e is retrofit2.HttpException && e.code() == 404) {
                // Already gone from the backend — delete locally below.
            } else throw e
        }
        dao.delete(id)
    }

    suspend fun setQueuePaused(paused: Boolean): Boolean {
        return runCatching {
            if (paused) api().pauseQueue() else api().resumeQueue()
            paused
        }.getOrElse { throw it }
    }

    suspend fun queuePaused(): Boolean =
        runCatching { api().queueState().paused }.getOrDefault(false)

    suspend fun refreshInsights(pageId: String? = null): InsightsDto? {
        return runCatching { api().insights(pageId) }.getOrNull()
    }

    companion object {
        fun iso(millis: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date(millis))
        }
    }
}

private fun com.postscheduler.data.remote.PostDto.toLocal(): Post {
    val type = runCatching { PostType.valueOf(type.uppercase()) }.getOrDefault(PostType.POST)
    val status = runCatching { PostStatus.valueOf(status.uppercase()) }.getOrDefault(PostStatus.SCHEDULED)
    val millis = scheduledAt?.let { parseIso(it) }
    // The backend returns each file's absolute URL. Without these the
    // local row has no media at all, so the Publish cards show no photo
    // and editing a story shows no image to keep or replace. For a video
    // the backend also returns a posterUrl — a JPEG frame pulled from the
    // video — and that is what the card shows, because decoding a frame
    // from a remote video URL on the phone is unreliable.
    val mediaUrls = (media ?: emptyList()).mapNotNull { it.posterUrl ?: it.url }.filter { it.isNotBlank() }
    val isVideo = (media ?: emptyList()).any { it.mimetype?.startsWith("video/") == true }
    return Post(
        id = id, type = type, caption = caption, hashtags = hashtags ?: "",
        mediaUris = mediaUrls.joinToString(","),
        mediaKind = when {
            type == PostType.REEL -> "video"
            isVideo -> "video"
            mediaUrls.isNotEmpty() -> "photo"
            else -> ""
        },
        scheduledAtMillis = millis, status = status,
        failureReason = failureReason, backendId = id, publishedPostId = facebookPostId,
        pageId = pageId
    )
}

/** Parse a backend ISO-8601 timestamp. The backend returns UTC `Z` times,
 * but the web writes them with milliseconds (`...T15:40:00.000Z`) while the
 * app writes them without (`...T16:13:00Z`) — both shapes must parse, or
 * the post's time comes back null and it vanishes from the Publish tab. */
private fun parseIso(iso: String): Long? {
    runCatching { return java.time.Instant.parse(iso).toEpochMilli() }
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(iso)?.time
    }.getOrNull()
}
