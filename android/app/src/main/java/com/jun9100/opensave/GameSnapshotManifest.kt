package com.jun9100.opensave

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class GameSnapshotLocation(
    val kind: String,
    val relativePath: String,
    val archivePath: String,
    val candidate: Boolean,
)

data class GameSnapshotManifest(
    val gameKey: String,
    val gameName: String,
    val locations: List<GameSnapshotLocation>,
) {
    fun write(file: File) {
        val json = JSONObject()
            .put("version", VERSION)
            .put("gameKey", gameKey)
            .put("gameName", gameName)
            .put("locations", JSONArray().apply {
                locations.forEach { location ->
                    put(
                        JSONObject()
                            .put("kind", location.kind)
                            .put("relativePath", location.relativePath)
                            .put("archivePath", location.archivePath)
                            .put("candidate", location.candidate),
                    )
                }
            })
        file.writeText(json.toString(), Charsets.UTF_8)
    }

    fun matches(game: DetectedGame): Boolean = gameKey == game.gameKey &&
        locations.map { it.relativePath }.toSet().all { path -> game.saves.any { it.relativePath == path } }

    companion object {
        const val FILE_NAME = "opensave-game.json"
        private const val VERSION = 1

        fun from(
            game: DetectedGame,
            saves: List<DetectedSave> = game.saves,
            gameName: String = game.presentation.primaryName,
        ): GameSnapshotManifest {
            require(saves.isNotEmpty()) { "游戏没有可备份的存档位置" }
            require(saves.all { save -> game.saves.any { it.relativePath == save.relativePath } }) {
                "备份位置不属于当前游戏清单"
            }
            val saves = saves
                .groupBy { it.relativePath }
                .map { (_, samePath) -> samePath.minBy { it.candidate } }
                .sortedBy { it.relativePath }
            val paths = saves.map { safeRelativePath(it.relativePath) }
            require(paths.none { it == "." }) { "不能把扫描根目录本身作为单游戏存档位置" }
            require(paths.none { current -> paths.any { other -> other != current && other.startsWith("$current/") } }) {
                "该游戏的存档位置互相包含，暂不支持安全地单独备份或恢复"
            }
            return GameSnapshotManifest(
                gameKey = game.gameKey,
                gameName = gameName,
                locations = saves.mapIndexed { index, save ->
                    GameSnapshotLocation(
                        kind = save.kind,
                        relativePath = safeRelativePath(save.relativePath),
                        archivePath = "location-${index.toString().padStart(4, '0')}",
                        candidate = save.candidate,
                    )
                },
            )
        }

        fun read(file: File): GameSnapshotManifest {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            require(json.optInt("version") == VERSION) { "不支持的游戏快照格式" }
            val gameKey = json.getString("gameKey")
            val gameName = json.getString("gameName")
            require(gameKey.isNotBlank() && gameName.isNotBlank()) { "游戏快照缺少游戏身份" }
            val locationsJson = json.getJSONArray("locations")
            val locations = (0 until locationsJson.length()).map { index ->
                val item = locationsJson.getJSONObject(index)
                val relativePath = safeRelativePath(item.getString("relativePath"))
                val archivePath = PathPolicy.requireSafeDocumentName(item.getString("archivePath"))
                require(archivePath.startsWith("location-")) { "游戏快照位置编号无效" }
                GameSnapshotLocation(
                    kind = item.getString("kind"),
                    relativePath = relativePath,
                    archivePath = archivePath,
                    candidate = item.optBoolean("candidate"),
                )
            }
            require(locations.isNotEmpty()) { "游戏快照没有存档位置" }
            require(locations.map { it.relativePath }.distinct().size == locations.size) {
                "游戏快照包含重复存档路径"
            }
            require(locations.none { current ->
                locations.any { other -> other != current && other.relativePath.startsWith("${current.relativePath}/") }
            }) { "游戏快照包含重叠存档路径" }
            return GameSnapshotManifest(
                gameKey = gameKey,
                gameName = gameName,
                locations = locations,
            )
        }

        fun safeRelativePath(value: String): String {
            require(value.isNotBlank() && !value.startsWith('/')) { "存档相对路径无效" }
            val components = value.replace('\\', '/').split('/')
            require(components.all(PathPolicy::isSafeDocumentName)) { "存档路径包含不安全的名称" }
            return components.joinToString("/")
        }
    }
}
