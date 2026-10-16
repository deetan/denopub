package dev.dt.kinopub.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authStore by preferencesDataStore("auth")

data class Tokens(val access: String, val refresh: String, val expiresAtMs: Long) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs - 60_000
}

/** Persists the OAuth tokens of this device. Single-user app, so one slot. */
class TokenStore(private val context: Context) {
    private val kAccess = stringPreferencesKey("access")
    private val kRefresh = stringPreferencesKey("refresh")
    private val kExpires = longPreferencesKey("expires_at")

    val tokens: Flow<Tokens?> = context.authStore.data.map { p ->
        val a = p[kAccess] ?: return@map null
        Tokens(a, p[kRefresh].orEmpty(), p[kExpires] ?: 0)
    }

    suspend fun current(): Tokens? = tokens.first()

    suspend fun save(access: String, refresh: String, expiresInSec: Long) {
        context.authStore.edit {
            it[kAccess] = access
            it[kRefresh] = refresh
            it[kExpires] = System.currentTimeMillis() + expiresInSec * 1000
        }
    }

    suspend fun clear() {
        context.authStore.edit { it.clear() }
    }
}
