package com.example.smstextapp.data

class MetadataRepository(private val metadataDao: MetadataDao) {
    suspend fun getPinnedThreadIds(): Set<Long> {
        return metadataDao.getPinnedThreadIds().toSet()
    }

    suspend fun setPinned(threadId: Long, isPinned: Boolean) {
        val existing = metadataDao.getMetadata(threadId)
        val newMetadata = if (existing == null) {
            ConversationMetadata(threadId = threadId, isPinned = isPinned)
        } else {
            existing.copy(isPinned = isPinned)
        }
        metadataDao.insertOrUpdate(newMetadata)
    }

    suspend fun setCustomSound(threadId: Long, soundUri: String?) {
        val existing = metadataDao.getMetadata(threadId)
        val newMetadata = if (existing == null) {
            ConversationMetadata(threadId = threadId, customSoundUri = soundUri)
        } else {
            existing.copy(customSoundUri = soundUri)
        }
        metadataDao.insertOrUpdate(newMetadata)
    }
}
