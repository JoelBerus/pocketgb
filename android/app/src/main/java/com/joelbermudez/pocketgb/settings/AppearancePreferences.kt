package com.joelbermudez.pocketgb.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance")

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

data class AppearanceState(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
) {
    companion object {
        val DEFAULT = AppearanceState(themeMode = ThemeMode.SYSTEM, dynamicColor = true)
    }
}

class AppearanceRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val themeMode = stringPreferencesKey("appearance_theme")
        val dynamicColor = booleanPreferencesKey("appearance_dynamic_color")
    }

    val state: Flow<AppearanceState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppearanceState(
                themeMode = preferences[Keys.themeMode]
                    ?.let { value -> runCatching { ThemeMode.valueOf(value) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                dynamicColor = preferences[Keys.dynamicColor] ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[Keys.themeMode] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.dynamicColor] = enabled }
    }
}
