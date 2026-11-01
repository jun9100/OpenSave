package com.jun9100.opensave

import android.content.Context

/**
 * The sources a background job may read without the UI: every selected SAF
 * tree that still grants read access and, with Root, every selected Root
 * source that exists. Shared by the auto-backup worker and [com.jun9100.opensave.batch.BatchWorker].
 */
internal object ScheduledSources {
    suspend fun readableSources(
        context: Context,
        storage: SafStorage,
        rootStorage: RootStorage,
        installedGameCatalog: InstalledGameCatalog,
    ): List<SaveSourceOption> {
        val sources = linkedMapOf<String, SaveSourceOption>()
        val selectionStore = SourceSelectionStore(context)
        val disabled = selectionStore.disabledSourceIds()
        storage.persistedTrees().filter { storage.hasReadPermission(it.uri) }.forEach { tree ->
            val source = SourceSelection.Saf(tree.uri)
            if (source.idSeed !in disabled) {
                sources[source.idSeed] = SaveSourceOption(
                    source = source,
                    label = tree.label,
                    saveType = GameSaveTypes.forSource(source),
                    requiredAccess = source.requiredAccessCapability(),
                    selected = true,
                )
            }
        }

        if (rootStorage.checkRootAccess().isFailure) return sources.values.toList()
        val rootOptions = SaveSourceOptions.build(
            authorizedTrees = emptyList(),
            rootPath = rootStorage.persistedRootPath(),
            disabledSourceIds = disabled,
            explicitlyEnabledSourceIds = selectionStore.explicitlyEnabledSourceIds(),
            rootPresets = RootSourcePresets.all +
                installedGameCatalog.rootPresets() +
                installedGameCatalog.candidateRootPresets(),
        )
        for (option in rootOptions.filter(SaveSourceOption::selected)) {
            val source = option.source as? SourceSelection.Root ?: continue
            if (rootStorage.pathExists(source.path).getOrDefault(false) && source.idSeed !in disabled) {
                sources[source.idSeed] = option
            }
        }
        return sources.values.toList()
    }

    /** Scans every readable source; one that fails to scan is left out. */
    suspend fun scan(context: Context): List<SourceScanResult> {
        val appContext = context.applicationContext
        val storage = SafStorage(appContext)
        val rootStorage = RootStorage(appContext)
        val catalog = InstalledGameCatalog(appContext)
        val androidGames = catalog.allApps()
        return readableSources(appContext, storage, rootStorage, catalog).mapNotNull { option ->
            when (val source = option.source) {
                is SourceSelection.Saf -> storage.scanTree(source.uri, androidGames).getOrNull()?.let { scan ->
                    SourceScanResult(source, option.label, scan, access = SourceAccessStatus.safAccessible(storage.hasWritePermission(source.uri)))
                }
                is SourceSelection.Root -> rootStorage.scanPath(source.path, androidGames).getOrNull()?.let { scan ->
                    SourceScanResult(source, option.label, scan, access = SourceAccessStatus.rootOnly())
                }
            }
        }
    }
}
