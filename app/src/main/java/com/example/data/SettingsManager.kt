package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_QUALITY = stringPreferencesKey("quality")
        val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play")
    }

    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { it[KEY_DARK_MODE] ?: true }
    val quality: Flow<String> = context.dataStore.data.map { it[KEY_QUALITY] ?: "Auto" }
    val isAutoPlay: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_PLAY] ?: true }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DARK_MODE] = enabled }
    }

    suspend fun setQuality(quality: String) {
        context.dataStore.edit { it[KEY_QUALITY] = quality }
    }

    suspend fun setAutoPlay(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_PLAY] = enabled }
    }
}
