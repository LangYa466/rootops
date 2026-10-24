package dev.rootops.ui.screens.ssh

import android.content.Context
import dev.rootops.core.api.SshAuth
import dev.rootops.core.api.SshProfile
import dev.rootops.core.util.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists [SshProfile] records in `filesDir/ssh_profiles.json`.
 *
 * The SFTP screen shares this file, so the on-disk shape is fixed:
 * ```
 * {"nextId":1,"profiles":[{"id":1,"name":"…","host":"…","port":22,"user":"…",
 *   "auth":"PASSWORD"|"KEY","password":"…","keyPath":"…","passphrase":"…"}]}
 * ```
 * Every public operation is synchronized on a process-wide lock so two stores
 * (SSH and SFTP screens) can never interleave a read-modify-write cycle.
 * Errors are swallowed: a corrupt or unreadable file reads as empty.
 */
class SshProfileStore private constructor(private val store: JsonStore) {

    /** Opens the shared profile file inside the app's private storage. */
    constructor(context: Context) : this(JsonStore(File(context.filesDir, FILE_NAME)))

    /** Opens an explicit file, for callers that already hold the path. */
    internal constructor(file: File) : this(JsonStore(file))

    /** All stored profiles, ordered as they appear in the file. */
    fun list(): List<SshProfile> = synchronized(LOCK) {
        try {
            readProfiles(store.read()).map(::decode)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Inserts [profile] when its id is unknown, otherwise replaces the record
     * with the same id. Returns the id the profile is stored under.
     */
    fun save(profile: SshProfile): Long = synchronized(LOCK) {
        try {
            val root = store.read()
            val entries = readProfiles(root).toMutableList()
            var nextId = root.optLong(KEY_NEXT_ID, 1L).coerceAtLeast(1L)
            for (entry in entries) {
                val id = entry.optLong(FIELD_ID, 0L)
                if (id >= nextId) nextId = id + 1L
            }
            val existingIndex = if (profile.id > 0L) {
                entries.indexOfFirst { it.optLong(FIELD_ID, -1L) == profile.id }
            } else {
                -1
            }
            val id = if (existingIndex >= 0) profile.id else nextId
            val encoded = encode(profile.copy(id = id))
            if (existingIndex >= 0) entries[existingIndex] = encoded else entries.add(encoded)

            val profiles = JSONArray()
            for (entry in entries) profiles.put(entry)
            val updated = JSONObject()
            updated.put(KEY_NEXT_ID, if (existingIndex >= 0) nextId else nextId + 1L)
            updated.put(KEY_PROFILES, profiles)
            store.write(updated)
            id
        } catch (t: Throwable) {
            profile.id
        }
    }

    /** Removes the profile with [id]; a missing id is a no-op. */
    fun delete(id: Long) {
        synchronized(LOCK) {
            try {
                val root = store.read()
                val entries = readProfiles(root)
                val kept = JSONArray()
                for (entry in entries) {
                    if (entry.optLong(FIELD_ID, -1L) != id) kept.put(entry)
                }
                if (kept.length() == entries.size) return@synchronized
                val updated = JSONObject()
                updated.put(KEY_NEXT_ID, root.optLong(KEY_NEXT_ID, 1L).coerceAtLeast(1L))
                updated.put(KEY_PROFILES, kept)
                store.write(updated)
            } catch (t: Throwable) {
                // Persistence failures must never break the UI.
            }
        }
    }

    /** Looks up a single profile by id. */
    fun get(id: Long): SshProfile? = synchronized(LOCK) {
        try {
            readProfiles(store.read())
                .firstOrNull { it.optLong(FIELD_ID, -1L) == id }
                ?.let(::decode)
        } catch (t: Throwable) {
            null
        }
    }

    private fun readProfiles(root: JSONObject): List<JSONObject> {
        val array = root.optJSONArray(KEY_PROFILES) ?: return emptyList()
        val result = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let(result::add)
        }
        return result
    }

    private fun encode(profile: SshProfile): JSONObject = JSONObject().apply {
        put(FIELD_ID, profile.id)
        put(FIELD_NAME, profile.name)
        put(FIELD_HOST, profile.host)
        put(FIELD_PORT, profile.port)
        put(FIELD_USER, profile.user)
        put(FIELD_AUTH, profile.auth.name)
        put(FIELD_PASSWORD, profile.password ?: "")
        put(FIELD_KEY_PATH, profile.keyPath ?: "")
        put(FIELD_PASSPHRASE, profile.passphrase ?: "")
    }

    private fun decode(json: JSONObject): SshProfile {
        val port = json.optInt(FIELD_PORT, DEFAULT_PORT).let {
            if (it in 1..65535) it else DEFAULT_PORT
        }
        val auth = when (json.optString(FIELD_AUTH, SshAuth.PASSWORD.name).uppercase()) {
            SshAuth.KEY.name -> SshAuth.KEY
            else -> SshAuth.PASSWORD
        }
        return SshProfile(
            id = json.optLong(FIELD_ID, 0L),
            name = json.optString(FIELD_NAME, ""),
            host = json.optString(FIELD_HOST, ""),
            port = port,
            user = json.optString(FIELD_USER, ""),
            auth = auth,
            password = json.optionalString(FIELD_PASSWORD),
            keyPath = json.optionalString(FIELD_KEY_PATH),
            passphrase = json.optionalString(FIELD_PASSPHRASE),
        )
    }

    companion object {
        const val FILE_NAME = "ssh_profiles.json"
        const val DEFAULT_PORT = 22

        private const val KEY_NEXT_ID = "nextId"
        private const val KEY_PROFILES = "profiles"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_HOST = "host"
        private const val FIELD_PORT = "port"
        private const val FIELD_USER = "user"
        private const val FIELD_AUTH = "auth"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_KEY_PATH = "keyPath"
        private const val FIELD_PASSPHRASE = "passphrase"

        private val LOCK = Any()

        /** Reads the shared profile file without constructing a store. */
        fun load(context: Context): List<SshProfile> = SshProfileStore(context).list()

        /** Reads one shared profile without constructing a store. */
        fun byId(context: Context, id: Long): SshProfile? = SshProfileStore(context).get(id)
    }
}

private fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.ifBlank { null }
}
