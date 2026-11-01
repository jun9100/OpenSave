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
        /** Called with the reason when Root is expected but su did not grant uid 0. */
        onRootUnavailable: ((String) -> Unit)? = null,
        /** Called with the labels of selected granted folders whose permission is gone. */
        onFoldersLost: ((List<String>) -> Unit)? = null,
    ): List<SaveSourceOption> {
        val sources = linkedMapOf<String, SaveSourceOption>()
        val selectionStore = SourceSelectionStore(context)
        val disabled = selectionStore.disabledSourceIds()
        val (readable, lost) = storage.persistedTrees().partition { storage.hasReadPermission(it.uri) }
        lost.filter { SourceSelection.Saf(it.uri).idSeed !in disabled }.map { it.label }
            .takeIf { it.isNotEmpty() }
            ?.let { onFoldersLost?.invoke(it) }
        readable.forEach { tree ->
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

        // su is never called before the user chose Root (first-run wizard).
        val onboarding = OnboardingStore(context)
        if (!onboarding.rootAllowed()) return sources.values.toList()
        val rootCheck = rootStorage.checkRootAccess()
        if (rootCheck.isFailure) {
            if (Onboarding.reportBackgroundRootFailure(rootAllowed = true, rootConfirmed = onboarding.rootConfirmed())) {
                onRootUnavailable?.invoke(rootCheck.exceptionOrNull()?.message ?: "su 未返回 uid 0")
            }
            return sources.values.toList()
        }
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
