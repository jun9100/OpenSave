package com.jun9100.opensave

import android.content.Context
import org.json.JSONObject

/**
 * What the last cloud check concluded for one game, kept across restarts so a
 * conflict or a newer cloud save is still shown after the app is reopened.
 * [recordId] is the game's newest ordinary snapshot when the check ran (empty
 * when it had none): once a backup or restore adds a newer one the verdict no
 * longer describes the local saves and is ignored.
 */
data class CloudVerdict(
    val action: GameSaveRemoteAction,
    val recordId: String,
)

/** Display-only memory of cloud checks; restores always re-check the cloud first. */
class CloudVerdictStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Map<String, CloudVerdict> = decode(preferences.getString(VERDICTS_KEY, null))

    /** Adds or replaces verdicts; [removedKeys] (games a check found up to date) are dropped first. */
    fun update(removedKeys: Set<String> = emptySet(), verdicts: Map<String, CloudVerdict> = emptyMap()) = synchronized(this) {
        val next = load() - removedKeys + verdicts
        preferences.edit().putString(VERDICTS_KEY, encode(next)).apply()
    }

    companion object {
        private const val PREFERENCES = "opensave_cloud_verdicts"
        private const val VERDICTS_KEY = "verdicts"

        fun encode(verdicts: Map<String, CloudVerdict>): String = JSONObject().apply {
            verdicts.forEach { (key, verdict) ->
                put(key, JSONObject().put("action", verdict.action.name).put("record", verdict.recordId))
            }
        }.toString()

        fun decode(json: String?): Map<String, CloudVerdict> {
            if (json.isNullOrBlank()) return emptyMap()
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            return root.keys().asSequence().mapNotNull { key ->
                val item = root.optJSONObject(key) ?: return@mapNotNull null
                val action = runCatching { GameSaveRemoteAction.valueOf(item.optString("action")) }.getOrNull()
                    ?: return@mapNotNull null
                key to CloudVerdict(action, item.optString("record"))
            }.toMap()
        }
    }
}
