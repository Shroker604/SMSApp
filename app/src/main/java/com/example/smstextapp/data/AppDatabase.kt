package com.example.smstextapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ConversationMetadata::class, ScheduledMessage::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun metadataDao(): MetadataDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
}
