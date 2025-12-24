package com.example.smstextapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MetadataDao {
    @Query("SELECT * FROM conversation_metadata WHERE threadId = :threadId")
    suspend fun getMetadata(threadId: Long): ConversationMetadata?

    @Query("SELECT * FROM conversation_metadata")
    suspend fun getAllMetadata(): List<ConversationMetadata>
    
    @Query("SELECT threadId FROM conversation_metadata WHERE isPinned = 1")
    suspend fun getPinnedThreadIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: ConversationMetadata)
    
    @Query("DELETE FROM conversation_metadata WHERE threadId = :threadId")
    suspend fun deleteMetadata(threadId: Long)
}
