// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package com.jun9100.opensave

import android.content.Context

/**
 * Everything the client remembers about "the cloud" is really an account's
 * history: the last head it published, the verdicts of a check, the scopes the
 * server deleted. Switching to another account (a tenant login, a different
 * server, a new token) makes all of it meaningless, and a stale head is worse
 * than no head: the backup refuses to publish because it believes the remote
 * version was lost.
 *
 * Clearing the three stores on a credential change keeps a fresh account usable
 * from the first scan.
 */
internal object GameSaveAccountState {
    fun reset(context: Context) {
        val app = context.applicationContext
        listOf(
            "opensave_game_save_heads",
            "opensave_cloud_verdicts",
            "opensave_scope_tombstones",
        ).forEach { name ->
            runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply() }
        }
    }
}
