package com.example.smstextapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalMessageDao {
    @Query("SELECT * FROM local_messages WHERE threadId = :threadId ORDER BY date ASC")
    fun getMessagesForThread(threadId: Long): Flow<List<LocalMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<LocalMessage>)

    @Query("DELETE FROM local_messages WHERE threadId = :threadId AND id >= 0")
    suspend fun clearSystemMessagesForThread(threadId: Long)
    
    @Query("DELETE FROM local_messages WHERE threadId = :threadId")
    suspend fun clearAllMessagesForThread(threadId: Long)
    
    @Query("DELETE FROM local_messages WHERE threadId = :threadId")
    suspend fun deleteByThreadId(threadId: Long)

    @Query("DELETE FROM local_messages WHERE id = :id")
    suspend fun deleteById(id: Long)
}
