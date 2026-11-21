package com.nexa.mobilebridge.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.nexa.mobilebridge.data.local.chat.ChatMessageDao
import com.nexa.mobilebridge.data.local.chat.NexaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("nexa_config")
        }
    }

    @Provides
    @Singleton
    fun provideNexaPreferencesDataStore(dataStore: DataStore<Preferences>): com.nexa.mobilebridge.data.local.NexaPreferencesDataStore {
        return com.nexa.mobilebridge.data.local.NexaPreferencesDataStore(dataStore)
    }

    @Provides
    @Singleton
    fun provideNexaDatabase(@ApplicationContext context: Context): NexaDatabase {
        return Room.databaseBuilder(
            context,
            NexaDatabase::class.java,
            "nexa_control_center.db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideChatMessageDao(db: NexaDatabase): ChatMessageDao = db.chatMessageDao()
}
