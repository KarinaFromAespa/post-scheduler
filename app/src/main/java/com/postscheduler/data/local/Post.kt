package com.postscheduler.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PostType { POST, REEL, STORY }
enum class PostStatus { DRAFT, SCHEDULED, PUBLISHING, PUBLISHED, FAILED }

@Entity(tableName = "posts")
data class Post(
    @PrimaryKey val id: String, // UUID, local first; backend id after sync
    val type: PostType,
    val caption: String,
    val hashtags: String = "",
    // Local content URIs, comma-separated. Uploaded to Object Storage by backend.
    val mediaUris: String = "",
    val mediaKind: String = "", // "photo" | "video" | ""
    val scheduledAtMillis: Long? = null, // null = draft / no time yet
    val status: PostStatus = PostStatus.DRAFT,
    val failureReason: String? = null,
    val backendId: String? = null,
    val publishedPostId: String? = null, // Facebook post id after publish
    val pageId: String? = null, // Facebook Page this post belongs to — the queue/drafts only show the selected Page's posts
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val mediaList: List<String>
        get() = mediaUris.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val isDraft: Boolean get() = status == PostStatus.DRAFT
}
