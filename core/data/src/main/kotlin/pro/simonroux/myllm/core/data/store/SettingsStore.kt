package pro.simonroux.myllm.core.data.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import pro.simonroux.myllm.core.model.AppSettings
import pro.simonroux.myllm.core.model.EngineDescriptor

private val Context.settingsDataStore by preferencesDataStore(name = "myllm_settings")

/**
 * Configuration, stored as one JSON blob per concern.
 *
 * A blob rather than a key per field: the settings object evolves constantly in
 * an app that rewrites itself, and adding a field with a default is a one-line
 * change here instead of a migration. Unknown keys are ignored on read, so an
 * APK rolled back to an older version still starts.
 */
class SettingsStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val engineListSerializer = ListSerializer(EngineDescriptor.serializer())

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        decode(preferences[SETTINGS_KEY], AppSettings.serializer(), AppSettings())
    }

    val engines: Flow<List<EngineDescriptor>> = context.settingsDataStore.data.map { preferences ->
        preferences[ENGINES_KEY]
            ?.let { runCatching { json.decodeFromString(engineListSerializer, it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun currentEngines(): List<EngineDescriptor> = engines.first()

    /** Read, modify, write in one transaction so concurrent edits cannot drop a field. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { preferences ->
            val existing = decode(preferences[SETTINGS_KEY], AppSettings.serializer(), AppSettings())
            preferences[SETTINGS_KEY] =
                json.encodeToString(AppSettings.serializer(), transform(existing))
        }
    }

    suspend fun updateEngines(transform: (List<EngineDescriptor>) -> List<EngineDescriptor>) {
        context.settingsDataStore.edit { preferences ->
            val existing = preferences[ENGINES_KEY]
                ?.let { runCatching { json.decodeFromString(engineListSerializer, it) }.getOrNull() }
                ?: emptyList()
            preferences[ENGINES_KEY] = json.encodeToString(engineListSerializer, transform(existing))
        }
    }

    /** Full configuration export, for backing up or moving to another phone. */
    suspend fun exportJson(): String {
        val settings = current()
        val engines = currentEngines()
        return json.encodeToString(
            ConfigurationBackup.serializer(),
            ConfigurationBackup(settings, engines),
        )
    }

    /**
     * Restores an export. Secrets are not part of it by design: an export is a
     * file that can end up in a chat thread, and API keys must not travel in one.
     */
    suspend fun importJson(raw: String): Result<Unit> = runCatching {
        val backup = json.decodeFromString(ConfigurationBackup.serializer(), raw)
        context.settingsDataStore.edit { preferences ->
            preferences[SETTINGS_KEY] =
                json.encodeToString(AppSettings.serializer(), backup.settings)
            preferences[ENGINES_KEY] =
                json.encodeToString(engineListSerializer, backup.engines)
        }
    }

    private fun <T> decode(
        raw: String?,
        serializer: kotlinx.serialization.KSerializer<T>,
        fallback: T,
    ): T = raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: fallback

    private companion object {
        val SETTINGS_KEY: Preferences.Key<String> = stringPreferencesKey("settings")
        val ENGINES_KEY: Preferences.Key<String> = stringPreferencesKey("engines")
    }
}

@kotlinx.serialization.Serializable
data class ConfigurationBackup(
    val settings: AppSettings,
    val engines: List<EngineDescriptor>,
    val formatVersion: Int = 1,
)
