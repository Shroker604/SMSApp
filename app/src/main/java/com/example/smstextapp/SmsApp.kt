package com.example.smstextapp

import android.app.Application
import com.example.smstextapp.data.ContactRepository

class AppContainer(context: android.content.Context) {
    val database = androidx.room.Room.databaseBuilder(
        context,
        com.example.smstextapp.data.AppDatabase::class.java, "sms-metadata-db"
    )
    .fallbackToDestructiveMigration()
    .build()

    val metadataRepository = com.example.smstextapp.data.MetadataRepository(database.metadataDao())
    val scheduledMessageRepository = com.example.smstextapp.data.ScheduledMessageRepository(context, database.scheduledMessageDao())
    val blockRepository = BlockRepository(context)
    val contactRepository = ContactRepository(context)
    val mmsRepository = com.example.smstextapp.data.MmsRepository(context, contactRepository)
    val smsManager: android.telephony.SmsManager = context.getSystemService(android.telephony.SmsManager::class.java)
    // SmsRepository will need metadataRepository soon
    val smsRepository = SmsRepository(
        context, 
        blockRepository, 
        contactRepository, 
        metadataRepository, 
        scheduledMessageRepository,
        database.localConversationDao(),
        database.localMessageDao(),
        mmsRepository
    )
}

class SmsApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        
        // Start Sync Engine
        container.smsRepository.registerObserver() // Listen for changes
        container.smsRepository.triggerSync()      // Initial Sync
        
        // Initialize MMS Library: Defaults are usually fine, or configured via SharedPrefs.
        // com.klinker.android.send_message.Settings(this) // Just init if needed, or rely on Transaction
    }
}
