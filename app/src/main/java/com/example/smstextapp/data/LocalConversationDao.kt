package com.example.smstextapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalConversationDao {
    @Query("SELECT * FROM local_conversations ORDER BY isPinned DESC, date DESC")
    fun getAllConversations(): Flow<List<LocalConversation>>

    @Query("SELECT * FROM local_conversations ORDER BY isPinned DESC, date DESC")
    suspend fun getAllConversationsSync(): List<LocalConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<LocalConversation>)

    @Query("DELETE FROM local_conversations")
    suspend fun clearAll()
    
    // For partial updates if needed
    @Query("UPDATE local_conversations SET isPinned = :isPinned WHERE threadId = :threadId")
    suspend fun updatePinStatus(threadId: Long, isPinned: Boolean)
}
