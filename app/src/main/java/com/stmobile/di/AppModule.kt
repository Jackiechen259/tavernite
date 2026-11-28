package com.stmobile.di

import android.content.Context
import androidx.room.Room
import com.stmobile.core.database.AppDatabase
import com.stmobile.core.database.CharacterDao
import com.stmobile.core.database.ChatDao
import com.stmobile.core.database.MessageDao
import com.stmobile.core.database.ServerDao
import com.stmobile.core.proxy.LocalProxyServer
import com.stmobile.core.proxy.UpstreamClient
import com.stmobile.core.settings.SettingsStore
import com.stmobile.core.update.GitHubReleaseClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "tavernite.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideServerDao(database: AppDatabase): ServerDao = database.serverDao()

    @Provides
    fun provideCharacterDao(database: AppDatabase): CharacterDao = database.characterDao()

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideUpstreamClient(): UpstreamClient = UpstreamClient()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun provideGitHubReleaseClient(okHttpClient: OkHttpClient): GitHubReleaseClient =
        GitHubReleaseClient(okHttpClient)

    @Provides
    @Singleton
    fun provideLocalProxy(
        upstream: UpstreamClient,
        @ApplicationContext context: Context
    ): LocalProxyServer = LocalProxyServer(
        upstream = upstream,
        cacheDir = File(context.cacheDir, "tavernite")
    )

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)
}
