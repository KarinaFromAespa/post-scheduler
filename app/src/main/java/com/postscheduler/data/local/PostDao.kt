package com.postscheduler.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    // Only the selected Page's posts. A post with no Page (created before
    // Page tracking, or from a client that didn't send one) still shows,
    // so legacy posts don't vanish — new posts always carry a Page.
    @Query("SELECT * FROM posts WHERE status IN ('SCHEDULED', 'FAILED', 'PUBLISHING') AND (pageId = :pageId OR pageId IS NULL) ORDER BY scheduledAtMillis ASC, createdAtMillis ASC")
    fun queue(pageId: String?): Flow<List<Post>>

    @Query("SELECT * FROM posts WHERE status = 'DRAFT' AND (pageId = :pageId OR pageId IS NULL) ORDER BY updatedAtMillis DESC")
    fun drafts(pageId: String?): Flow<List<Post>>

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    suspend fun get(id: String): Post?

    @Query("SELECT * FROM posts WHERE status = 'PUBLISHED' AND (pageId = :pageId OR pageId IS NULL) ORDER BY scheduledAtMillis DESC")
    fun published(pageId: String?): Flow<List<Post>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: Post)

    @Update
    suspend fun update(post: Post)

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: String)

    /** Removes a stale local row for a backend post when the same post is
     * also stored under its backend id — i.e. the row created locally with
     * a random UUID before the backend assigned an id. Without this, one
     * scheduled post shows up twice in the queue. */
    @Query("DELETE FROM posts WHERE backendId = :backendId AND id != :backendId")
    suspend fun deleteDuplicatesForBackendId(backendId: String)
}
