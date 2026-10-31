package com.jun9100.opensave.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jun9100.opensave.CopyReport
import com.jun9100.opensave.DetectedGame
import com.jun9100.opensave.DetectedSave
import com.jun9100.opensave.FileRecord
import com.jun9100.opensave.GameIconKey
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.GamePresentation
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.SaveScanReport
import com.jun9100.opensave.SnapshotRecord
import com.jun9100.opensave.SnapshotNaming
import com.jun9100.opensave.SourceAccessStatus
import com.jun9100.opensave.SourceGameEntry
import com.jun9100.opensave.SourceScanResult
import com.jun9100.opensave.SourceSelection
import com.jun9100.opensave.SourceUiState

/**
 * Odin2 window sizes measured in android/docs/odin2-input.md (833x444dp
 * landscape, ~468x809dp portrait), each at the default and a 1.3 font scale.
 */
@Preview(name = "Odin2 横屏", widthDp = 833, heightDp = 444, showBackground = true)
@Preview(name = "Odin2 横屏 · 字体 1.3", widthDp = 833, heightDp = 444, fontScale = 1.3f, showBackground = true)
@Preview(name = "Odin2 竖屏", widthDp = 468, heightDp = 809, showBackground = true)
@Preview(name = "Odin2 竖屏 · 字体 1.3", widthDp = 468, heightDp = 809, fontScale = 1.3f, showBackground = true)
internal annotation class OdinScreenPreviews

/** Component-sized previews in light, dark and a 1.3 font scale. */
@Preview(name = "浅色", widthDp = 420, showBackground = true)
@Preview(name = "深色", widthDp = 420, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "字体 1.3", widthDp = 420, fontScale = 1.3f, showBackground = true)
internal annotation class ComponentPreviews

@Composable
internal fun PreviewSurface(content: @Composable () -> Unit) {
    OpenSaveTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.padding(12.dp)) { content() }
        }
    }
}

/** Fake library data for previews; never touches storage or the network. */
internal object PreviewData {
    private val rootSource = SourceSelection.Root("/data/user/0/dev.eden.eden_emulator/files")

    val switchGame = DetectedGame(
        gameKey = "switch:0100F2C0115B6000",
        presentation = GamePresentation(
            localizedName = "塞尔达传说 王国之泪",
            originalName = "The Legend of Zelda: Tears of the Kingdom",
            identity = "TitleID 0100F2C0115B6000",
            iconKey = GameIconKey.SWITCH,
        ),
        saves = listOf(
            DetectedSave(
                kind = "yuzu-switch",
                relativePath = "nand/user/save/0000000000000000/0123456789ABCDEF/0100F2C0115B6000",
                titleId = "0100F2C0115B6000",
                note = "Yuzu/Eden 用户存档",
            ),
            DetectedSave(
                kind = "yuzu-switch",
                relativePath = "nand/user/save/cache/0100F2C0115B6000",
                titleId = "0100F2C0115B6000",
                note = "缓存目录",
                candidate = true,
            ),
        ),
    )

    val pcGame = DetectedGame(
        gameKey = "pc:hollow-knight",
        presentation = GamePresentation(
            localizedName = null,
            originalName = "Hollow Knight",
            identity = null,
            iconKey = GameIconKey.PC,
            steamAppId = "367520",
        ),
        saves = listOf(
            DetectedSave(
                kind = "gamehub-pc",
                relativePath = "drive_c/users/xuser/AppData/LocalLow/Team Cherry/Hollow Knight",
                note = "GameHub 本地 PC",
            ),
        ),
    )

    val switchEntry = entry(switchGame)
    val pcEntry = entry(pcGame, label = "GameHub PC", access = SourceAccessStatus.rootOnly())

    val report = CopyReport(
        fileCount = 3,
        byteCount = 1_572_864,
        manifestHash = "9f2c4a1e0b7d5c3a8e6f1d2b4c6a8e0f1a3c5e7b9d1f3a5c7e9b1d3f5a7c9e1b",
        files = listOf(
            FileRecord("nand/user/save/0100F2C0115B6000/progress.sav", 1_048_576, "a1"),
            FileRecord("nand/user/save/0100F2C0115B6000/option.sav", 2_048, "b2"),
            FileRecord("nand/user/save/0100F2C0115B6000/caption.jpg", 522_240, "c3"),
        ),
        detected = switchGame.saves,
    )

    val snapshots = listOf(
        snapshot(id = "a", createdAtMillis = 1_793_000_000_000, uploaded = true),
        snapshot(id = "b", createdAtMillis = 1_792_900_000_000, uploaded = false, comment = "恢复前安全副本"),
    )

    /** A library large enough to count rows: the Switch game, Hollow Knight and a dozen more titles. */
    val libraryState: SourceUiState
        get() {
            val extra = (1..12).map { index ->
                DetectedSave(
                    kind = "yuzu-switch",
                    relativePath = "nand/user/save/0000000000000000/0123456789ABCDEF/01000000000${"%05X".format(index)}",
                    titleId = "01000000000${"%05X".format(index)}",
                    note = "Yuzu/Eden 用户存档",
                    displayName = "示例游戏 $index：名字很长的副标题用于检查单行省略",
                )
            }
            val scan = SaveScanReport(
                rootPath = rootSource.path,
                scannedDirectoryCount = 120,
                detected = switchGame.saves + pcGame.saves + extra,
            )
            val scans = listOf(SourceScanResult(source = rootSource, label = "Eden 模拟器", scan = scan))
            val first = GameLibrary.sourceGames(scans).first()
            return SourceUiState(
                sourceScans = scans,
                snapshots = snapshots.map { it.copy(sourceId = first.sourceId, gameKey = first.game.gameKey) },
                librarySelection = setOf(first.libraryKey),
            )
        }

    private fun snapshot(id: String, createdAtMillis: Long, uploaded: Boolean, comment: String = "") = SnapshotRecord(
        id = id,
        sourceId = switchEntry.sourceId,
        remoteName = "",
        localPath = "",
        createdAtMillis = createdAtMillis,
        sizeBytes = 1_572_864,
        sha256 = "",
        manifestHash = "",
        uploaded = uploaded,
        comment = comment,
        gameKey = switchGame.gameKey,
        gameName = "塞尔达传说 王国之泪",
        saveType = "switch",
    )

    private fun entry(
        game: DetectedGame,
        label: String = "Eden 模拟器",
        access: SourceAccessStatus = SourceAccessStatus.appAccessible(),
    ) = SourceGameEntry(
        source = rootSource,
        sourceId = SnapshotNaming.sourceId(rootSource.idSeed),
        sourceLabel = label,
        sourceAccess = access,
        game = game,
    )
}
