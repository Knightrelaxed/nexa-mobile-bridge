package com.nexa.mobilebridge.data.local.chat

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NexaDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
}
