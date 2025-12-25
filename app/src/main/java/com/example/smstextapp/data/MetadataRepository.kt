package com.example.smstextapp.data

class MetadataRepository(private val dao: MetadataDao) {
    suspend fun getPinnedThreadIds(): Set<Long> {
        return dao.getPinnedThreadIds().toSet()
    }
    
    suspend fun setPinned(threadId: Long, isPinned: Boolean) {
        dao.insertOrUpdate(ConversationMetadata(threadId, isPinned))
    }
}
