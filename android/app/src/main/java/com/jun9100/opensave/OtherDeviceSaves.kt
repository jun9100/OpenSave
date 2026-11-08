package com.jun9100.opensave

/** Read-only state of the other devices' save locations for one library entry. */
sealed interface OtherDeviceSaves {
    data object Loading : OtherDeviceSaves
    data class Loaded(val members: List<GameSaveCloudGameMember>, val atMillis: Long) : OtherDeviceSaves
    data class Failed(val message: String, val atMillis: Long) : OtherDeviceSaves
}

/**
 * A verified other-device archive and the restore plan the user is editing.
 * Not a data class: [plan] is an internal type, so the request cannot expose a
 * public copy function.
 */
class OtherDeviceRestoreRequest internal constructor(
    val entry: SourceGameEntry,
    val member: GameSaveCloudGameMember,
    /** Absolute path of the verified cached archive. */
    val archivePath: String,
    internal val plan: CrossDevicePlan,
) {
    /** The same request with an edited plan; only the dialog changes parts. */
    internal fun withPlan(plan: CrossDevicePlan): OtherDeviceRestoreRequest =
        OtherDeviceRestoreRequest(entry, member, archivePath, plan)
}
