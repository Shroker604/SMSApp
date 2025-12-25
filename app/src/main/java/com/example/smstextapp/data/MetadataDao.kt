package com.example.smstextapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

@Dao
interface MetadataDao {
    @Query("SELECT threadId FROM conversation_metadata WHERE isPinned = 1")
    suspend fun getPinnedThreadIds(): List<Long>
    
    // Simple insert/update
     @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: ConversationMetadata)
}
