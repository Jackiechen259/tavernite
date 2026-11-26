package com.stmobile.di

import android.content.Context
import com.stmobile.core.proxy.LocalProxyServer
import com.stmobile.core.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideLocalProxy(@ApplicationContext context: Context): LocalProxyServer =
        LocalProxyServer(cacheDir = File(context.cacheDir, "tavernite"))

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)
}
