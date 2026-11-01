package com.jun9100.opensave.batch

import com.jun9100.opensave.GameSyncStatus

/** Which games of a scope each batch operation takes (plan §5.4); pure, unit tested. */
object BatchEligibility {
    /** "上传待传": games whose newest backup never reached game-save. */
    fun pendingUpload(scope: List<String>, statuses: Map<String, GameSyncStatus>): List<String> =
        scope.filter { statuses[it] == GameSyncStatus.NOT_UPLOADED }

    /**
     * "安全恢复云端较新": only games whose cloud save is strictly newer and
     * safe ([GameSyncStatus.CLOUD_NEWER]). Conflicts are never resolved by a
     * batch; they are returned separately so the plan can list them as 需确认.
     */
    fun safeRestore(scope: List<String>, statuses: Map<String, GameSyncStatus>): SafeRestorePlan = SafeRestorePlan(
        restore = scope.filter { statuses[it] == GameSyncStatus.CLOUD_NEWER },
        conflicts = scope.filter { statuses[it] == GameSyncStatus.CONFLICT },
    )
}

data class SafeRestorePlan(val restore: List<String>, val conflicts: List<String>)
