package com.example.smstextapp

import android.app.Application
import com.example.smstextapp.data.ContactRepository

class AppContainer(context: android.content.Context) {
    val blockRepository = BlockRepository(context)
    val contactRepository = ContactRepository(context)
    val smsRepository = SmsRepository(context, blockRepository, contactRepository)
}

class SmsApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
