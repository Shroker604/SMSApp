package com.example.smstextapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ScheduledMessage::class, 
        ConversationMetadata::class,
        LocalConversation::class, // New
        LocalMessage::class       // New
    ], 
    version = 5, 
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun metadataDao(): MetadataDao
    abstract fun localConversationDao(): LocalConversationDao // New
    abstract fun localMessageDao(): LocalMessageDao           // New
}
