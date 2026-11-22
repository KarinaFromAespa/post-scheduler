package com.postscheduler.data.remote

import com.postscheduler.data.local.PostStatus
import com.postscheduler.data.local.PostType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

data class MediaDto(
    val url: String?,
    val mimetype: String?,
    val filename: String?,
    val posterUrl: String? = null
)

data class PostDto(
    val id: String,
    val type: String,
    val caption: String,
    val hashtags: String?,
    val scheduledAt: String?, // ISO-8601
    val status: String,
    val failureReason: String?,
    val facebookPostId: String?,
    val pageId: String? = null,
    val media: List<MediaDto>? = null
)

data class CreatePostRequest(
    val type: String,
    val caption: String,
    val hashtags: String? = null,
    val scheduledAt: String?, // ISO-8601, null = draft
    val status: String,
    val publishNow: Boolean = false,
    val pageId: String? = null,
    val tempMediaIds: List<String>? = null
)

/** Edit a scheduled post or draft: the same fields as create, all optional
 * except the ones being changed. The backend PATCHes the same post. */
data class UpdatePostRequest(
    val type: String? = null,
    val caption: String? = null,
    val hashtags: String? = null,
    val scheduledAt: String? = null,
    val status: String? = null,
    val pageId: String? = null,
    val tempMediaIds: List<String>? = null
)

// A file staged by the background upload (POST /api/media/temp). The id
// is what the post links to on save; the file itself is already on the
// server, so saving never waits on an upload.
data class TempMediaDto(
    val id: String,
    val filename: String,
    val mimetype: String?,
    val url: String?
)

data class PageDto(
    val id: String,
    val name: String,
    val pictureUrl: String? = null
)

/** Sent after Facebook Login: the Page the user picked plus its Page token,
 * so the backend can publish as that Page. The backend needs a matching
 * POST /api/pages/connect endpoint; until it has one the app still saves
 * the choice locally and the token stays on the phone. */
data class ConnectPageRequest(
    val pageId: String,
    val pageName: String,
    val accessToken: String,
    val pictureUrl: String? = null
)

data class QueueStateDto(val paused: Boolean)
data class InsightsDto(
    val monetized: Boolean,
    val monetizationCriteria: String?,
    val totalEarningsUsd: Double?
)

interface PlannerApi {
    @GET("api/pages")
    suspend fun pages(): List<PageDto>

    @POST("api/pages/connect")
    suspend fun connectPage(@Body req: ConnectPageRequest): PageDto

    @DELETE("api/pages/{id}")
    suspend fun deletePage(@Path("id") id: String)

    @GET("api/queue")
    suspend fun queue(): List<PostDto>

    @GET("api/posts")
    suspend fun allPosts(): List<PostDto>

    @GET("api/drafts")
    suspend fun drafts(): List<PostDto>

    @POST("api/posts")
    suspend fun createPost(@Body req: CreatePostRequest): PostDto

    @PATCH("api/posts/{id}")
    suspend fun updatePost(@Path("id") id: String, @Body req: UpdatePostRequest): PostDto

    @DELETE("api/posts/{id}")
    suspend fun deletePost(@Path("id") id: String)

    @DELETE("api/posts/{id}/media")
    suspend fun clearMedia(@Path("id") id: String): PostDto

    @Multipart
    @POST("api/posts/{id}/media")
    suspend fun uploadMedia(
        @Path("id") id: String,
        @Part file: MultipartBody.Part
    ): PostDto

    // Background upload: stage a picked file on the server before the post
    // exists, so saving never waits on an upload. The returned id is what
    // the post links to (tempMediaIds) on save.
    @Multipart
    @POST("api/media/temp")
    suspend fun uploadTempMedia(
        @Part file: MultipartBody.Part
    ): TempMediaDto

    // Remove a staged file the user took off before saving.
    @DELETE("api/media/temp/{id}")
    suspend fun deleteTempMedia(@Path("id") id: String)

    @POST("api/posts/{id}/publish-now")
    suspend fun publishNow(@Path("id") id: String): PostDto

    @GET("api/queue/state")
    suspend fun queueState(): QueueStateDto

    @POST("api/queue/pause")
    suspend fun pauseQueue(): QueueStateDto

    @POST("api/queue/resume")
    suspend fun resumeQueue(): QueueStateDto

    @GET("api/insights")
    suspend fun insights(@Query("pageId") pageId: String? = null): InsightsDto
}

fun PostType.toApi(): String = name.lowercase()
fun PostStatus.toApi(): String = name.lowercase()
