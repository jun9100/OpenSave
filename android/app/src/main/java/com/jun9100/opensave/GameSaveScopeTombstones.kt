package com.jun9100.opensave

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * The server's verdict on one `{type}/{gameId}` scope, kept on this device so
 * every later sync can stop before sending a request. The server keeps the
 * same record permanently; this copy only remembers it until the user acts.
 */
internal data class ScopeTombstone(
    val type: String,
    val gameId: String,
    /** [STATE_DELETED] or [STATE_RETIRED]. */
    val state: String,
    /** The server's `deletedAt`; blank when the response did not carry one. */
    val at: String = "",
    val successorType: String = "",
    val successorGameId: String = "",
    /** Filled later by the UI from /games; may stay blank. */
    val successorName: String = "",
    /** The user chose 不再同步这个游戏. */
    val acknowledged: Boolean = false,
    val seenAtMillis: Long,
) {
    val retired: Boolean get() = state == STATE_RETIRED

    /** The 动态/批量任务 wording: a stopped scope is a state, not a failure. */
    val stopNotice: String
        get() = if (retired) RETIRED_NOTICE else DELETED_NOTICE

    companion object {
        const val STATE_DELETED = "deleted"
        const val STATE_RETIRED = "retired"

        const val DELETED_NOTICE = "云端存档已被删除"
        const val RETIRED_NOTICE = "已合并到其他游戏"
    }
}

/** What a stopped scope means for the user, for paths that only hold the 410. */
internal val GameSaveScopeGoneException.stopNotice: String
    get() = when {
        retired -> ScopeTombstone.RETIRED_NOTICE
        deleted -> ScopeTombstone.DELETED_NOTICE
        else -> message.orEmpty()
    }

/**
 * The one decision every background and batch path asks before touching a
 * game: a recorded tombstone stops the scope until the user clears it.
 */
internal object ScopeTombstonePolicy {
    fun shouldSkip(tombstone: ScopeTombstone?): Boolean = tombstone != null
}

/**
 * The tombstone bookkeeping the sync paths need. An interface so the native
 * sync can be unit-tested without Android's SharedPreferences.
 */
internal interface ScopeTombstoneRepository {
    fun get(identity: GameCloudIdentity): ScopeTombstone?
    fun all(): List<ScopeTombstone>
    fun record(identity: GameCloudIdentity, error: GameSaveScopeGoneException): Boolean
    fun acknowledge(identity: GameCloudIdentity)
    fun setSuccessorName(identity: GameCloudIdentity, name: String)
    fun clear(identity: GameCloudIdentity)
}

/**
 * Tombstones of this install, keyed by `{type}/{gameId}`. Only `deleted` and
 * `retired` are recorded: any other 410 is a plain error the server may fix.
 *
 * The UI reads [revision] to refresh when a worker records or clears one, like
 * [com.jun9100.opensave.activity.ActivityLogStore.revision].
 */
internal class ScopeTombstoneStore(context: Context) : ScopeTombstoneRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun get(identity: GameCloudIdentity): ScopeTombstone? = synchronized(LOCK) {
        read(preferences, key(identity))
    }

    override fun all(): List<ScopeTombstone> = synchronized(LOCK) {
        preferences.all.values
            .mapNotNull { value -> (value as? String)?.let(ScopeTombstoneCodec::decode) }
            .sortedWith(compareBy({ it.type }, { it.seenAtMillis }, { it.gameId }))
    }

    /**
     * Records the server's verdict. The acknowledgement and the successor name
     * belong to the same state: a scope that changed from deleted to retired
     * asks the user again.
     */
    override fun record(identity: GameCloudIdentity, error: GameSaveScopeGoneException): Boolean {
        val state = when {
            error.deleted -> ScopeTombstone.STATE_DELETED
            error.retired -> ScopeTombstone.STATE_RETIRED
            // An unknown 410 may be a temporary refusal; it must not stop the game forever.
            else -> return false
        }
        return synchronized(LOCK) {
            val key = key(identity)
            val existing = read(preferences, key)
            val previous = existing?.takeIf { it.state == state }
            val recorded = ScopeTombstone(
                type = identity.saveType.id,
                gameId = identity.gameId,
                state = state,
                at = error.deletedAt,
                successorType = error.successorType,
                successorGameId = error.successorGameId,
                successorName = previous?.successorName.orEmpty(),
                acknowledged = previous?.acknowledged ?: false,
                // The first sighting orders the list; a repeated verdict is not a new event.
                seenAtMillis = previous?.seenAtMillis ?: System.currentTimeMillis(),
            )
            if (recorded == existing) {
                false
            } else {
                write(preferences, key, recorded)
                changes.update { it + 1 }
                true
            }
        }
    }

    override fun acknowledge(identity: GameCloudIdentity) = update(identity) { it.copy(acknowledged = true) }

    override fun setSuccessorName(identity: GameCloudIdentity, name: String) =
        update(identity) { it.copy(successorName = name.trim().take(MAX_SUCCESSOR_NAME_LENGTH)) }

    override fun clear(identity: GameCloudIdentity) {
        synchronized(LOCK) {
            if (preferences.contains(key(identity))) {
                preferences.edit().remove(key(identity)).apply()
                changes.update { it + 1 }
            }
        }
    }

    private fun update(identity: GameCloudIdentity, change: (ScopeTombstone) -> ScopeTombstone) {
        synchronized(LOCK) {
            val key = key(identity)
            val current = read(preferences, key) ?: return
            val updated = change(current)
            if (updated == current) return
            write(preferences, key, updated)
            changes.update { it + 1 }
        }
    }

    private fun key(identity: GameCloudIdentity): String = ScopeTombstoneCodec.key(identity.saveType.id, identity.gameId)

    internal companion object {
        private const val PREFERENCES = "opensave_scope_tombstones"
        private const val MAX_SUCCESSOR_NAME_LENGTH = 120

        /** Process-wide: the ViewModel, the workers and the batch share the same file. */
        private val LOCK = Any()
        private val changes = MutableStateFlow(0L)

        /** Grows with every recorded, acknowledged or cleared tombstone in this process. */
        val revision: StateFlow<Long> = changes.asStateFlow()

        private fun read(preferences: SharedPreferences, key: String): ScopeTombstone? =
            preferences.getString(key, null)?.let(ScopeTombstoneCodec::decode)

        private fun write(preferences: SharedPreferences, key: String, tombstone: ScopeTombstone) {
            preferences.edit().putString(key, ScopeTombstoneCodec.encode(tombstone)).apply()
        }
    }
}

/**
 * The on-disk shape of one tombstone. Kept separate from the store so the JSON
 * round trip can be tested without Android.
 */
internal object ScopeTombstoneCodec {
    fun key(type: String, gameId: String): String = "$type/$gameId"

    fun encode(tombstone: ScopeTombstone): String = JSONObject()
        .put("type", tombstone.type)
        .put("gameId", tombstone.gameId)
        .put("state", tombstone.state)
        .put("at", tombstone.at)
        .put("successorType", tombstone.successorType)
        .put("successorGameId", tombstone.successorGameId)
        .put("successorName", tombstone.successorName)
        .put("acknowledged", tombstone.acknowledged)
        .put("seenAtMillis", tombstone.seenAtMillis)
        .toString()

    /** A malformed or unknown record is dropped; one bad entry must not stop the app. */
    fun decode(text: String): ScopeTombstone? = runCatching {
        val json = JSONObject(text)
        val type = json.optString("type").trim().lowercase()
        val gameId = json.optString("gameId").trim()
        val state = json.optString("state").trim()
        if (!GameSaveTypes.isValid(type) || gameId.isBlank()) return null
        if (state != ScopeTombstone.STATE_DELETED && state != ScopeTombstone.STATE_RETIRED) return null
        ScopeTombstone(
            type = type,
            gameId = gameId,
            state = state,
            at = json.optString("at").trim(),
            successorType = json.optString("successorType").trim().lowercase(),
            successorGameId = json.optString("successorGameId").trim(),
            successorName = json.optString("successorName").trim(),
            acknowledged = json.optBoolean("acknowledged"),
            seenAtMillis = json.optLong("seenAtMillis"),
        )
    }.getOrNull()
}

/** The cloud scope [this] game is published to, resolved exactly like every publish path. */
internal fun SourceGameEntry.cloudScopeIdentity(): GameCloudIdentity =
    GameCloudNaming.identity(source, sourceId, game.copy(saves = GameLibrary.batchBackupLocations(game)))
