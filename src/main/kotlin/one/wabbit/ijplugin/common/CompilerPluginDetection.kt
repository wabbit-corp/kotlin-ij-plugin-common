// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.ijplugin.common

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.facet.KotlinFacet

data class CompilerPluginDescriptor(val compilerPluginMarker: String, val gradlePluginId: String)

data class GradleBuildFileMatch(val relativePath: String, val rootPath: String)

data class CompilerPluginMatch(val ownerName: String, val classpaths: List<String>)

private data class VersionCatalogPluginAlias(val catalogName: String, val accessor: String)

private data class VersionCatalogPluginAliasDefinition(
    val catalogName: String,
    val accessor: String,
    val gradlePluginId: String,
)

private data class ParsedVersionCatalogPluginAliasDefinition(
    val accessor: String,
    val gradlePluginId: String,
)

private data class GradleVersionCatalogSource(val catalogName: String, val file: Path)

private data class DirectGradlePluginApplication(val gradlePluginId: String, val endExclusive: Int)

private data class VersionCatalogAliasApplication(
    val alias: VersionCatalogPluginAlias,
    val endExclusive: Int,
)

private data class ParsedGradleBuildScriptApplications(
    val directPluginIds: Set<String>,
    val versionCatalogAliases: Set<VersionCatalogPluginAlias>,
)

internal data class CompilerPluginModuleSnapshot(
    val name: String,
    val contentRoots: List<Path>,
    val compilerPluginClasspaths: List<String>,
)

internal data class CompilerPluginProjectSnapshot(
    val projectName: String,
    val projectBasePath: Path?,
    val projectCompilerPluginClasspaths: List<String>,
    val moduleSnapshots: List<CompilerPluginModuleSnapshot>,
)

data class CompilerPluginScan(
    val projectLevelMatch: CompilerPluginMatch?,
    val moduleMatches: List<CompilerPluginMatch>,
    val gradleBuildFiles: List<String>,
    val gradleImportPaths: List<String> = emptyList(),
    val gradleDetectionSignature: String? = null,
) {
    val hasImportedCompilerPluginMatches: Boolean
        get() = projectLevelMatch != null || moduleMatches.isNotEmpty()

    val hasGradleBuildFileMatches: Boolean
        get() = gradleBuildFiles.isNotEmpty()

    val hasMatches: Boolean
        get() = hasImportedCompilerPluginMatches || hasGradleBuildFileMatches

    val requiresGradleImport: Boolean
        get() = hasGradleBuildFileMatches && !hasImportedCompilerPluginMatches
}

private enum class GradleTokenKind {
    IDENTIFIER,
    STRING,
    SYMBOL,
    NEWLINE,
}

private data class GradleToken(val kind: GradleTokenKind, val text: String)

private data class CachedFileText(
    val lastModifiedMillis: Long,
    val size: Long,
    val text: String,
    val computedValues: MutableMap<String, Any> = mutableMapOf(),
    val computationCounts: MutableMap<String, Int> = mutableMapOf(),
)

internal object GradleFileTextCache {
    private const val MAX_ENTRIES = 256
    internal const val TOKENS_CACHE_KEY = "gradle-tokens"
    internal const val BUILD_SCRIPT_APPLICATIONS_CACHE_KEY = "gradle-build-script-applications"
    internal const val PARSED_SETTINGS_CACHE_KEY_PREFIX = "parsed-settings:"
    internal const val VERSION_CATALOG_PLUGIN_ALIAS_DEFINITIONS_CACHE_KEY =
        "version-catalog-plugin-alias-definitions"
    private val lock = Any()
    private val entries = LinkedHashMap<Path, CachedFileText>(MAX_ENTRIES, 0.75f, true)

    fun read(path: Path): String? {
        return readEntry(path)?.text
    }

    fun <T : Any> getOrCompute(path: Path, key: String, compute: (String) -> T): T? {
        val entry = readEntry(path) ?: return null
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val cached = entry.computedValues[key] as T?
            if (cached != null) {
                return cached
            }
            val computed = compute(entry.text)
            entry.computedValues[key] = computed
            entry.computationCounts[key] = (entry.computationCounts[key] ?: 0) + 1
            return computed
        }
    }

    internal fun clearForTesting() {
        synchronized(lock) {
            entries.clear()
        }
    }

    internal fun sizeForTesting(): Int =
        synchronized(lock) {
            entries.size
        }

    internal fun containsForTesting(path: Path): Boolean =
        synchronized(lock) {
            entries.containsKey(path.toAbsolutePath().normalize())
        }

    internal fun computationCountForTesting(path: Path, key: String): Int =
        synchronized(lock) {
            entries[path.toAbsolutePath().normalize()]?.computationCounts?.get(key) ?: 0
        }

    internal fun maxEntriesForTesting(): Int = MAX_ENTRIES

    private fun readEntry(path: Path): CachedFileText? {
        val normalizedPath = path.toAbsolutePath().normalize()
        return runCatching {
                val lastModifiedMillis = Files.getLastModifiedTime(normalizedPath).toMillis()
                val size = Files.size(normalizedPath)
                synchronized(lock) {
                    entries[normalizedPath]
                        ?.takeIf { entry ->
                            entry.lastModifiedMillis == lastModifiedMillis && entry.size == size
                        }
                        ?.let { entry ->
                            return entry
                        }
                }
                val text = Files.readString(normalizedPath)
                val entry =
                    CachedFileText(
                        lastModifiedMillis = lastModifiedMillis,
                        size = size,
                        text = text,
                    )
                synchronized(lock) {
                    entries[normalizedPath] = entry
                    trimToMaxSizeLocked()
                }
                entry
            }
            .getOrElse {
                synchronized(lock) {
                    entries.remove(normalizedPath)
                }
                null
            }
    }

    private fun trimToMaxSizeLocked() {
        while (entries.size > MAX_ENTRIES) {
            val eldestKey = entries.entries.iterator().next().key
            entries.remove(eldestKey)
        }
    }
}

object CompilerPluginDetector {
    fun scan(project: Project, descriptor: CompilerPluginDescriptor): CompilerPluginScan {
        return scan(
            project = project,
            descriptor = descriptor,
            readProjectSnapshot = ::readProjectSnapshot,
        )
    }

    internal fun scan(
        project: Project,
        descriptor: CompilerPluginDescriptor,
        readProjectSnapshot: (Project) -> CompilerPluginProjectSnapshot,
        runReadAction: (() -> CompilerPluginProjectSnapshot) -> CompilerPluginProjectSnapshot = {
            action ->
            ReadAction.compute<CompilerPluginProjectSnapshot, RuntimeException> { action() }
        },
    ): CompilerPluginScan {
        val projectSnapshot = runReadAction { readProjectSnapshot(project) }
        return scan(projectSnapshot = projectSnapshot, descriptor = descriptor)
    }

    internal fun scan(
        projectSnapshot: CompilerPluginProjectSnapshot,
        descriptor: CompilerPluginDescriptor,
    ): CompilerPluginScan {
        val gradleDetection =
            detectGradleBuildFiles(
                projectRoots = gradleBuildScanRoots(projectSnapshot),
                gradlePluginId = descriptor.gradlePluginId,
            )
        val projectClasspaths =
            matchingClasspaths(
                classpaths = projectSnapshot.projectCompilerPluginClasspaths,
                compilerPluginMarker = descriptor.compilerPluginMarker,
            )
        val projectMatch =
            projectClasspaths
                .takeIf { it.isNotEmpty() }
                ?.let { classpaths ->
                    CompilerPluginMatch(
                        ownerName = projectSnapshot.projectName,
                        classpaths = classpaths,
                    )
                }
        val moduleMatches =
            projectSnapshot.moduleSnapshots.mapNotNull { moduleSnapshot ->
                scanModule(
                    moduleSnapshot = moduleSnapshot,
                    compilerPluginMarker = descriptor.compilerPluginMarker,
                )
            }
        return CompilerPluginScan(
            projectLevelMatch = projectMatch,
            moduleMatches = moduleMatches,
            gradleBuildFiles = gradleDetection.matches.map { match -> match.relativePath },
            gradleImportPaths =
                gradleDetection.matches.map { match -> match.rootPath }.distinct().sorted(),
            gradleDetectionSignature = gradleDetection.signature,
        )
    }

    fun matchingClasspaths(
        classpaths: Iterable<String>,
        compilerPluginMarker: String,
    ): List<String> =
        classpaths
            .filter { classpath ->
                isCompilerPluginPath(
                    classpath = classpath,
                    compilerPluginMarker = compilerPluginMarker,
                )
            }
            .distinct()

    fun isCompilerPluginPath(classpath: String, compilerPluginMarker: String): Boolean {
        val normalized = classpath.replace('\\', '/').lowercase()
        return normalized.contains(compilerPluginMarker.lowercase())
    }

    fun matchingGradleBuildFiles(projectRoot: Path, gradlePluginId: String): List<String> =
        matchingGradleBuildFileMatches(
                projectRoots = listOf(projectRoot),
                gradlePluginId = gradlePluginId,
            )
            .map { match -> match.relativePath }

    fun matchingGradleBuildFiles(
        projectRoots: Iterable<Path>,
        gradlePluginId: String,
    ): List<String> {
        return matchingGradleBuildFileMatches(
                projectRoots = projectRoots,
                gradlePluginId = gradlePluginId,
            )
            .map { match -> match.relativePath }
    }

    fun matchingGradleBuildFileMatches(
        projectRoots: Iterable<Path>,
        gradlePluginId: String,
    ): List<GradleBuildFileMatch> =
        detectGradleBuildFiles(
                projectRoots = projectRoots,
                gradlePluginId = gradlePluginId,
            )
            .matches

    internal fun gradleScanRoots(projectRoots: Iterable<Path>): List<Path> {
        val normalizedRoots =
            projectRoots
                .map { root -> root.toAbsolutePath().normalize() }
                .filter { root -> Files.isDirectory(root) }
                .distinct()
                .sortedWith(compareBy<Path>({ it.nameCount }, { it.toString() }))
        if (normalizedRoots.isEmpty()) {
            return emptyList()
        }
        val coveredDescendantRoots = mutableSetOf<Path>()
        return buildList {
            normalizedRoots.forEach { root ->
                if (root in coveredDescendantRoots) {
                    return@forEach
                }
                add(root)
                coveredDescendantRoots += coveredGradleDescendantRoots(root)
            }
        }
    }

    internal fun watchedGradleRoots(projectRoots: Iterable<Path>): List<Path> =
        gradleScanRoots(projectRoots)
            .flatMap { root -> gradleScanTargets(root).flatMap(::watchedGradleDirectories) }
            .distinct()
            .sortedBy(Path::toString)

    private fun watchedGradleDirectories(scanTarget: GradleScanTarget): List<Path> =
        buildList {
                add(scanTarget.root)
                scanTarget.watchedFiles.mapNotNull(Path::getParent).forEach(::add)
            }
            .map { path -> path.toAbsolutePath().normalize() }
            .distinct()

    private data class GradleBuildFileDetection(
        val matches: List<GradleBuildFileMatch>,
        val signature: String?,
    )

    private fun detectGradleBuildFiles(
        projectRoots: Iterable<Path>,
        gradlePluginId: String,
    ): GradleBuildFileDetection {
        val roots = gradleScanRoots(projectRoots)
        if (roots.isEmpty()) {
            return GradleBuildFileDetection(matches = emptyList(), signature = null)
        }
        val displayRoot = commonAncestor(roots)
        val scanTargets =
            roots
                .flatMap { root -> gradleScanTargets(root) }
                .groupBy(GradleScanTarget::root)
                .values
                .map { duplicates ->
                    duplicates.reduce { left, right ->
                        GradleScanTarget(
                            root = left.root,
                            buildScripts = (left.buildScripts + right.buildScripts).distinct(),
                            settingsScripts =
                                (left.settingsScripts + right.settingsScripts).distinct(),
                            versionCatalogs =
                                (left.versionCatalogs + right.versionCatalogs)
                                    .distinctBy { source ->
                                        source.catalogName to source.file
                                    },
                            versionCatalogAliasDefinitions =
                                left.versionCatalogAliasDefinitions +
                                    right.versionCatalogAliasDefinitions,
                        )
                    }
                }
                .sortedBy(GradleScanTarget::root)
        val versionCatalogAliasesByRoot =
            scanTargets.associate { scanTarget ->
                val programmaticAliases =
                    scanTarget.versionCatalogAliasDefinitions
                        .asSequence()
                        .filter { definition ->
                            definition.gradlePluginId == gradlePluginId.lowercase()
                        }
                        .map { definition ->
                            VersionCatalogPluginAlias(
                                catalogName = definition.catalogName,
                                accessor = definition.accessor,
                            )
                        }
                        .toSet()
                scanTarget.root to
                    (programmaticAliases +
                        scanTarget.versionCatalogs.flatMap { source ->
                            cachedVersionCatalogPluginAliases(
                                file = source.file,
                                catalogName = source.catalogName,
                                gradlePluginId = gradlePluginId,
                            )
                                .orEmpty()
                        })
                        .toSet()
            }
        val buildScripts =
            scanTargets.flatMap { scanTarget ->
                val versionCatalogAliases = versionCatalogAliasesByRoot.getValue(scanTarget.root)
                scanTarget.buildScripts.mapNotNull { file ->
                    val normalizedFile = file.toAbsolutePath().normalize()
                    cachedGradleBuildScriptApplications(file)?.let { applications ->
                        GradleBuildScriptCandidate(
                            file = normalizedFile,
                            match =
                                GradleBuildFileMatch(
                                    relativePath =
                                        displayGradleBuildFilePath(
                                            normalizedFile = normalizedFile,
                                            displayRoot = displayRoot,
                                            scanTargetRoot = scanTarget.root,
                                        ),
                                    rootPath = scanTarget.root.toString().replace('\\', '/'),
                                ),
                            root = scanTarget.root,
                            applications = applications,
                            versionCatalogAliases = versionCatalogAliases,
                        )
                    }
                }
            }
        val matches =
            buildScripts
                .filter { candidate ->
                    candidate.applications.directPluginIds.contains(gradlePluginId.lowercase()) ||
                        candidate.applications.versionCatalogAliases.any(candidate.versionCatalogAliases::contains)
                }
                .groupBy(GradleBuildScriptCandidate::file)
                .values
                .map { duplicates ->
                    duplicates.minWith(
                        compareBy<GradleBuildScriptCandidate>(
                            { it.root.nameCount },
                            { it.root.toString() },
                        )
                    )
                }
                .map(GradleBuildScriptCandidate::match)
                .sortedWith(
                    compareBy(GradleBuildFileMatch::relativePath, GradleBuildFileMatch::rootPath)
                )
        return GradleBuildFileDetection(
            matches = matches,
            signature = gradleDetectionSignature(scanTargets),
        )
    }

    private fun cachedGradleBuildScriptApplications(file: Path): ParsedGradleBuildScriptApplications? =
        GradleFileTextCache.getOrCompute(
            path = file,
            key = GradleFileTextCache.BUILD_SCRIPT_APPLICATIONS_CACHE_KEY,
        ) {
            parseGradleBuildScriptApplications(cachedGradleTokens(file).orEmpty())
        }

    private fun cachedGradleTokens(file: Path): List<GradleToken>? =
        GradleFileTextCache.getOrCompute(
            path = file,
            key = GradleFileTextCache.TOKENS_CACHE_KEY,
            compute = ::tokenizeGradleScript,
        )

    private fun cachedParsedGradleSettings(
        projectRoot: Path,
        file: Path,
    ): ParsedGradleSettings? =
        GradleFileTextCache.getOrCompute(
            path = file,
            key =
                GradleFileTextCache.PARSED_SETTINGS_CACHE_KEY_PREFIX +
                    projectRoot.toAbsolutePath().normalize(),
        ) {
            parseGradleSettings(projectRoot = projectRoot, tokens = cachedGradleTokens(file).orEmpty())
        }

    private fun cachedVersionCatalogPluginAliases(
        file: Path,
        catalogName: String,
        gradlePluginId: String,
    ): Set<VersionCatalogPluginAlias>? =
        cachedVersionCatalogPluginAliasDefinitions(file)
            ?.asSequence()
            ?.filter { definition -> definition.gradlePluginId == gradlePluginId.lowercase() }
            ?.map { definition ->
                VersionCatalogPluginAlias(catalogName = catalogName, accessor = definition.accessor)
            }
            ?.toSet()

    private fun cachedVersionCatalogPluginAliasDefinitions(
        file: Path
    ): Set<ParsedVersionCatalogPluginAliasDefinition>? =
        GradleFileTextCache.getOrCompute(
            path = file,
            key = GradleFileTextCache.VERSION_CATALOG_PLUGIN_ALIAS_DEFINITIONS_CACHE_KEY,
        ) {
            parseVersionCatalogPluginAliasDefinitions(it)
        }

    fun isDirectGradlePluginReference(content: String, gradlePluginId: String): Boolean {
        return hasDirectGradlePluginApplication(
            tokens = tokenizeGradleScript(content),
            gradlePluginId = gradlePluginId,
        )
    }

    private fun readProjectSnapshot(project: Project): CompilerPluginProjectSnapshot {
        val modules = ModuleManager.getInstance(project).modules
        return CompilerPluginProjectSnapshot(
            projectName = project.name,
            projectBasePath = project.basePath?.let(Path::of),
            projectCompilerPluginClasspaths =
                KotlinCommonCompilerArgumentsHolder.getInstance(project)
                    .settings
                    .pluginClasspaths
                    .orEmpty()
                    .asList(),
            moduleSnapshots =
                modules.map { module ->
                    CompilerPluginModuleSnapshot(
                        name = module.name,
                        contentRoots =
                            ModuleRootManager.getInstance(module).contentRoots.map { root ->
                                Path.of(root.path)
                            },
                        compilerPluginClasspaths =
                            KotlinFacet.get(module)
                                ?.configuration
                                ?.settings
                                ?.mergedCompilerArguments
                                ?.pluginClasspaths
                                .orEmpty()
                                .asList(),
                    )
                },
        )
    }

    private fun scanModule(
        moduleSnapshot: CompilerPluginModuleSnapshot,
        compilerPluginMarker: String,
    ): CompilerPluginMatch? {
        val classpaths =
            matchingClasspaths(
                classpaths = moduleSnapshot.compilerPluginClasspaths,
                compilerPluginMarker = compilerPluginMarker,
            )
        if (classpaths.isEmpty()) {
            return null
        }
        return CompilerPluginMatch(ownerName = moduleSnapshot.name, classpaths = classpaths)
    }

    private fun gradleBuildScanRoots(projectSnapshot: CompilerPluginProjectSnapshot): List<Path> {
        val roots = mutableListOf<Path>()
        projectSnapshot.projectBasePath?.let(roots::add)
        projectSnapshot.moduleSnapshots.forEach { moduleSnapshot ->
            roots.addAll(moduleSnapshot.contentRoots)
        }
        return gradleScanRoots(roots)
    }

    private fun coveredGradleDescendantRoots(projectRoot: Path): Set<Path> =
        gradleSettingsScriptCandidates(projectRoot)
            .asSequence()
            .mapNotNull { file -> cachedParsedGradleSettings(projectRoot = projectRoot, file = file) }
            .flatMap { parsedSettings ->
                (parsedSettings.projectDirectories.asSequence() +
                        parsedSettings.includedBuildRoots.asSequence())
                    .map(Path::normalize)
            }
            .filter { descendantRoot ->
                descendantRoot != projectRoot && descendantRoot.startsWith(projectRoot)
            }
            .toSet()

    private fun commonAncestor(paths: List<Path>): Path? {
        var common = paths.firstOrNull() ?: return null
        for (path in paths.drop(1)) {
            while (!path.startsWith(common)) {
                common = common.parent ?: return null
            }
        }
        return common
    }

    private fun gradleDetectionSignature(scanTargets: List<GradleScanTarget>): String? =
        scanTargets
            .asSequence()
            .flatMap { scanTarget -> scanTarget.watchedFiles.asSequence() }
            .mapNotNull(::watchedFileSignature)
            .distinct()
            .sorted()
            .joinToString(separator = "|")
            .ifEmpty { null }

    private fun watchedFileSignature(path: Path): String? =
        runCatching {
                val normalizedPath = path.toAbsolutePath().normalize()
                val lastModifiedMillis = Files.getLastModifiedTime(normalizedPath).toMillis()
                val size = Files.size(normalizedPath)
                "${normalizedPath}:${lastModifiedMillis}:${size}"
            }
            .getOrNull()

    private data class GradleScanTarget(
        val root: Path,
        val buildScripts: List<Path>,
        val settingsScripts: List<Path>,
        val versionCatalogs: List<GradleVersionCatalogSource>,
        val versionCatalogAliasDefinitions: Set<VersionCatalogPluginAliasDefinition>,
    ) {
        val watchedFiles: List<Path>
            get() =
                (
                    buildScripts +
                        settingsScripts +
                        versionCatalogs.map(GradleVersionCatalogSource::file)
                    )
                    .distinct()
    }

    private data class GradleBuildScriptCandidate(
        val file: Path,
        val match: GradleBuildFileMatch,
        val root: Path,
        val applications: ParsedGradleBuildScriptApplications,
        val versionCatalogAliases: Set<VersionCatalogPluginAlias>,
    )

    private fun gradleScanTargets(projectRoot: Path): List<GradleScanTarget> =
        discoverGradleBuilds(
                projectRoot = projectRoot.toAbsolutePath().normalize(),
                visitedRoots = linkedSetOf(),
            )
            .map { discovered ->
                GradleScanTarget(
                    root = discovered.root,
                    buildScripts = discovered.buildScripts.sorted(),
                    settingsScripts = discovered.settingsScripts.sorted(),
                    versionCatalogs =
                        discovered.versionCatalogs.sortedWith(
                            compareBy<GradleVersionCatalogSource>(
                                { source -> source.catalogName },
                                { source -> source.file.toString() },
                            )
                        ),
                    versionCatalogAliasDefinitions = discovered.versionCatalogAliasDefinitions,
                )
            }

    private data class DiscoveredGradleBuild(
        val root: Path,
        val buildScripts: Set<Path>,
        val settingsScripts: Set<Path>,
        val versionCatalogs: Set<GradleVersionCatalogSource>,
        val versionCatalogAliasDefinitions: Set<VersionCatalogPluginAliasDefinition>,
    )

    private data class ParsedGradleSettings(
        val defaultLibrariesExtensionName: String?,
        val projectDirectories: Set<Path>,
        val projectBuildScripts: Set<Path>,
        val includedBuildRoots: Set<Path>,
        val versionCatalogs: Set<GradleVersionCatalogSource>,
        val versionCatalogAliasDefinitions: Set<VersionCatalogPluginAliasDefinition>,
    )

    private fun discoverGradleBuilds(
        projectRoot: Path,
        visitedRoots: MutableSet<Path>,
    ): List<DiscoveredGradleBuild> {
        if (!Files.isDirectory(projectRoot) || !visitedRoots.add(projectRoot)) {
            return emptyList()
        }
        val settingsScripts = gradleSettingsScriptCandidates(projectRoot)
        val parsedSettings =
            settingsScripts.mapNotNull { file ->
                cachedParsedGradleSettings(projectRoot = projectRoot, file = file)
            }
        val defaultLibrariesExtensionName =
            parsedSettings
                .asSequence()
                .mapNotNull(ParsedGradleSettings::defaultLibrariesExtensionName)
                .lastOrNull()
        val discoveredBuilds = mutableListOf<DiscoveredGradleBuild>()
        discoveredBuilds +=
            DiscoveredGradleBuild(
                root = projectRoot,
                buildScripts =
                    buildSet {
                        addAll(gradleBuildScriptsAt(projectRoot))
                        addAll(gradleConventionPluginScriptsAt(projectRoot))
                        parsedSettings.forEach { parsed ->
                            addAll(parsed.projectBuildScripts)
                        }
                    },
                versionCatalogs =
                    buildSet {
                        addAll(
                            gradleVersionCatalogCandidates(
                                projectRoot = projectRoot,
                                defaultLibrariesExtensionName = defaultLibrariesExtensionName,
                            )
                        )
                        parsedSettings.forEach { parsed -> addAll(parsed.versionCatalogs) }
                    },
                versionCatalogAliasDefinitions =
                    buildSet {
                        parsedSettings.forEach { parsed ->
                            addAll(parsed.versionCatalogAliasDefinitions)
                        }
                    },
                settingsScripts = settingsScripts.toSet(),
            )
        parsedSettings
            .flatMap(ParsedGradleSettings::includedBuildRoots)
            .distinct()
            .forEach { includedBuildRoot ->
                discoveredBuilds += discoverGradleBuilds(includedBuildRoot, visitedRoots)
            }
        return discoveredBuilds
    }

    private fun gradleBuildScriptsAt(directory: Path): List<Path> =
        listOf(directory.resolve("build.gradle"), directory.resolve("build.gradle.kts")).filter {
            path -> Files.isRegularFile(path)
        }

    private fun gradleConventionPluginScriptsAt(buildRoot: Path): List<Path> =
        listOf(
                buildRoot.resolve("src/main/kotlin"),
                buildRoot.resolve("src/main/groovy"),
                buildRoot.resolve("buildSrc/src/main/kotlin"),
                buildRoot.resolve("buildSrc/src/main/groovy"),
            )
            .flatMap(::recursiveRegularChildren)
            .filter { path -> isGradleBuildScriptFileCandidate(path.fileName?.toString()) }
            .distinct()

    private fun gradleSettingsScriptCandidates(projectRoot: Path): List<Path> =
        directRegularChildren(projectRoot).filter { path ->
            isGradleSettingsScriptFileCandidate(path.fileName?.toString())
        }

    internal fun displayGradleBuildFilePath(
        normalizedFile: Path,
        displayRoot: Path?,
        scanTargetRoot: Path,
    ): String {
        val relativePath =
            displayRoot
                ?.takeIf { normalizedFile.startsWith(it) }
                ?.let { root -> runCatching { root.relativize(normalizedFile).normalize() }.getOrNull() }
                ?: runCatching { scanTargetRoot.relativize(normalizedFile).normalize() }.getOrNull()
                ?: normalizedFile
        return relativePath.toString().replace('\\', '/')
    }

    private fun gradleVersionCatalogCandidates(
        projectRoot: Path,
        defaultLibrariesExtensionName: String?,
    ): List<GradleVersionCatalogSource> {
        val defaultCatalogFile = projectRoot.resolve("gradle/libs.versions.toml")
        if (!Files.isRegularFile(defaultCatalogFile)) {
            return emptyList()
        }
        return listOf(
            GradleVersionCatalogSource(
                catalogName = defaultLibrariesExtensionName?.lowercase() ?: "libs",
                file = defaultCatalogFile,
            )
        )
    }

    private fun directRegularChildren(directory: Path): List<Path> =
        runCatching {
                Files.list(directory).use { paths ->
                    paths.filter { path -> Files.isRegularFile(path) }.toList()
                }
            }
            .getOrDefault(emptyList())

    private fun recursiveRegularChildren(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        return runCatching {
                Files.walk(directory).use { paths ->
                    paths.filter { path -> Files.isRegularFile(path) }.toList()
                }
            }
            .getOrDefault(emptyList())
    }

    private fun isGradleBuildScriptFileCandidate(fileName: String?): Boolean {
        fileName ?: return false
        return fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts")
    }

    private fun isGradleSettingsScriptFileCandidate(fileName: String?): Boolean {
        fileName ?: return false
        return fileName == "settings.gradle" || fileName == "settings.gradle.kts"
    }

    private fun isGradleVersionCatalogFileCandidate(fileName: String?): Boolean {
        fileName ?: return false
        return fileName.endsWith(".versions.toml")
    }

    private fun parseGradleSettings(projectRoot: Path, tokens: List<GradleToken>): ParsedGradleSettings {
        val includedProjectPaths = mutableSetOf<String>()
        val includedFlatProjectDirectories = mutableSetOf<Path>()
        val projectDirectoryOverrides = mutableMapOf<String, Path>()
        val projectBuildFileNameOverrides = mutableMapOf<String, String>()
        var defaultLibrariesExtensionName: String? = null
        val includedBuildRoots = mutableSetOf<Path>()
        val versionCatalogs = mutableSetOf<GradleVersionCatalogSource>()
        val versionCatalogAliasDefinitions = mutableSetOf<VersionCatalogPluginAliasDefinition>()
        tokens.indices.forEach { index ->
            includedProjectPaths += includedGradleProjectPaths(tokens, index)
            includedFlatProjectDirectories += includedFlatProjectDirectories(projectRoot, tokens, index)
            parseGradleProjectDirectoryOverride(projectRoot, tokens, index)?.let { override ->
                projectDirectoryOverrides[override.first] = override.second
            }
            parseGradleProjectBuildFileNameOverride(tokens, index)?.let { override ->
                projectBuildFileNameOverrides[override.first] = override.second
            }
            parseDefaultLibrariesExtensionName(tokens, index)?.let { defaultLibrariesExtensionName = it }
            includedBuildRoots += includedGradleBuildRoots(projectRoot, tokens, index)
            versionCatalogs += settingsDefinedVersionCatalogSources(projectRoot, tokens, index)
            versionCatalogAliasDefinitions +=
                settingsDefinedVersionCatalogPluginAliases(tokens, index)
        }
        val projectDirectories =
            buildSet {
                includedProjectPaths.forEach { projectPath ->
                    val normalizedProjectPath = normalizeGradleProjectPath(projectPath) ?: return@forEach
                    add(
                        projectDirectoryOverrides[normalizedProjectPath]
                            ?: projectRoot.resolve(
                                requireNotNull(includedGradleProjectDirectory(normalizedProjectPath))
                            )
                    )
                }
                addAll(includedFlatProjectDirectories)
            }
        val projectBuildScripts =
            buildSet {
                includedProjectPaths.forEach { projectPath ->
                    val normalizedProjectPath = normalizeGradleProjectPath(projectPath) ?: return@forEach
                    val projectDirectory =
                        projectDirectoryOverrides[normalizedProjectPath]
                            ?: projectRoot.resolve(
                                requireNotNull(includedGradleProjectDirectory(normalizedProjectPath))
                            )
                    val buildFileName = projectBuildFileNameOverrides[normalizedProjectPath]
                    if (buildFileName != null) {
                        val buildScript = projectDirectory.resolve(buildFileName)
                        if (Files.isRegularFile(buildScript)) {
                            add(buildScript)
                        }
                    } else {
                        addAll(gradleBuildScriptsAt(projectDirectory))
                    }
                }
                includedFlatProjectDirectories.forEach { directory ->
                    addAll(gradleBuildScriptsAt(directory))
                }
            }
        return ParsedGradleSettings(
            defaultLibrariesExtensionName = defaultLibrariesExtensionName?.lowercase(),
            projectDirectories = projectDirectories.map(Path::normalize).toSet(),
            projectBuildScripts = projectBuildScripts.map(Path::normalize).toSet(),
            includedBuildRoots = includedBuildRoots.map(Path::normalize).toSet(),
            versionCatalogs =
                versionCatalogs
                    .filter { source -> Files.isRegularFile(source.file) }
                    .map { source -> source.copy(file = source.file.normalize()) }
                    .toSet(),
            versionCatalogAliasDefinitions = versionCatalogAliasDefinitions,
        )
    }

    private fun parseVersionCatalogPluginAliasDefinitions(
        content: String
    ): Set<ParsedVersionCatalogPluginAliasDefinition> {
        val pluginsSection =
            tomlSectionContent(stripCommentsPreservingStrings(content).lowercase(), "plugins")
                ?: return emptySet()
        val aliasPattern = Regex("""(?ms)^\s*([a-z0-9_.-]+)\s*=\s*\{(.*?)\}""")
        val pluginIdPattern = Regex("""\bid\s*=\s*["']([^"']+)["']""")
        return aliasPattern.findAll(pluginsSection).mapNotNullTo(mutableSetOf()) { match ->
            val pluginId =
                pluginIdPattern.find(match.groupValues[2])?.groupValues?.getOrNull(1) ?: return@mapNotNullTo null
            ParsedVersionCatalogPluginAliasDefinition(
                accessor = aliasToGradleAccessor(match.groupValues[1]),
                gradlePluginId = pluginId,
            )
        }
    }

    private fun includedGradleProjectPaths(tokens: List<GradleToken>, index: Int): List<String> {
        if (!tokens.getOrNull(index).isIdentifier("include")) {
            return emptyList()
        }
        return stringCallArguments(tokens = tokens, index = index)
            .mapNotNull(::normalizeGradleProjectPath)
            .flatMap(::expandGradleProjectPathHierarchy)
            .distinct()
    }

    private fun expandGradleProjectPathHierarchy(projectPath: String): List<String> {
        val normalized = normalizeGradleProjectPath(projectPath) ?: return emptyList()
        val segments = normalized.removePrefix(":").split(':').filter(String::isNotEmpty)
        if (segments.isEmpty()) {
            return emptyList()
        }
        return buildList(segments.size) {
            for (index in segments.indices) {
                add(":" + segments.subList(0, index + 1).joinToString(":"))
            }
        }
    }

    private fun includedFlatProjectDirectories(
        projectRoot: Path,
        tokens: List<GradleToken>,
        index: Int,
    ): List<Path> {
        if (!tokens.getOrNull(index).isIdentifier("includeFlat")) {
            return emptyList()
        }
        val parent = projectRoot.parent ?: return emptyList()
        return stringCallArguments(tokens = tokens, index = index)
            .map { argument -> parent.resolve(argument).normalize() }
            .distinct()
    }

    private fun includedGradleBuildRoots(
        projectRoot: Path,
        tokens: List<GradleToken>,
        index: Int,
    ): List<Path> {
        if (!tokens.getOrNull(index).isIdentifier("includeBuild")) {
            return emptyList()
        }
        return stringCallArguments(tokens = tokens, index = index)
            .map { argument -> projectRoot.resolve(argument).normalize() }
            .filter { root -> Files.isDirectory(root) }
            .distinct()
    }

    private fun stringCallArguments(tokens: List<GradleToken>, index: Int): List<String> {
        val next = nextCodeTokenIndex(tokens, index + 1) ?: return emptyList()
        return when {
            tokens[next].kind == GradleTokenKind.STRING ->
                inlineIncludeArguments(tokens = tokens, start = next)
            tokens[next].isSymbol("(") -> parenthesizedIncludeArguments(tokens = tokens, open = next)
            else -> emptyList()
        }
    }

    private fun parseGradleProjectDirectoryOverride(
        projectRoot: Path,
        tokens: List<GradleToken>,
        index: Int,
    ): Pair<String, Path>? {
        if (!tokens.getOrNull(index).isIdentifier("project")) {
            return null
        }
        val open = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (!tokens[open].isSymbol("(")) {
            return null
        }
        val projectPathIndex = nextCodeTokenIndex(tokens, open + 1) ?: return null
        val close = matchingCloseParenIndex(tokens, open) ?: return null
        if (projectPathIndex >= close || tokens[projectPathIndex].kind != GradleTokenKind.STRING) {
            return null
        }
        val projectPath = normalizeGradleProjectPath(tokens[projectPathIndex].text) ?: return null
        val dot = nextCodeTokenIndex(tokens, close + 1) ?: return null
        val property = nextCodeTokenIndex(tokens, dot + 1) ?: return null
        val equals = nextCodeTokenIndex(tokens, property + 1) ?: return null
        val fileCall = nextCodeTokenIndex(tokens, equals + 1) ?: return null
        val fileOpen = nextCodeTokenIndex(tokens, fileCall + 1) ?: return null
        val filePathIndex = nextCodeTokenIndex(tokens, fileOpen + 1) ?: return null
        if (
            !tokens[dot].isSymbol(".") ||
                !tokens[property].isIdentifier("projectDir") ||
                !tokens[equals].isSymbol("=") ||
                !tokens[fileCall].isIdentifier("file") ||
                !tokens[fileOpen].isSymbol("(") ||
                tokens[filePathIndex].kind != GradleTokenKind.STRING
        ) {
            return null
        }
        return projectPath to projectRoot.resolve(tokens[filePathIndex].text).normalize()
    }

    private fun parseGradleProjectBuildFileNameOverride(
        tokens: List<GradleToken>,
        index: Int,
    ): Pair<String, String>? {
        if (!tokens.getOrNull(index).isIdentifier("project")) {
            return null
        }
        val open = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (!tokens[open].isSymbol("(")) {
            return null
        }
        val projectPathIndex = nextCodeTokenIndex(tokens, open + 1) ?: return null
        val close = matchingCloseParenIndex(tokens, open) ?: return null
        if (projectPathIndex >= close || tokens[projectPathIndex].kind != GradleTokenKind.STRING) {
            return null
        }
        val projectPath = normalizeGradleProjectPath(tokens[projectPathIndex].text) ?: return null
        val dot = nextCodeTokenIndex(tokens, close + 1) ?: return null
        val property = nextCodeTokenIndex(tokens, dot + 1) ?: return null
        val equals = nextCodeTokenIndex(tokens, property + 1) ?: return null
        val fileNameIndex = nextCodeTokenIndex(tokens, equals + 1) ?: return null
        if (
            !tokens[dot].isSymbol(".") ||
                !tokens[property].isIdentifier("buildFileName") ||
                !tokens[equals].isSymbol("=") ||
                tokens[fileNameIndex].kind != GradleTokenKind.STRING
        ) {
            return null
        }
        return projectPath to tokens[fileNameIndex].text
    }

    private fun parseDefaultLibrariesExtensionName(
        tokens: List<GradleToken>,
        index: Int,
    ): String? {
        if (!tokens.getOrNull(index).isIdentifier("defaultLibrariesExtensionName")) {
            return null
        }
        val equals = nextCodeTokenIndex(tokens, index + 1) ?: return null
        val valueIndex = nextCodeTokenIndex(tokens, equals + 1) ?: return null
        if (!tokens[equals].isSymbol("=")) {
            return null
        }
        return resolveStringToken(
            token = tokens.getOrNull(valueIndex),
            stringBindings = stringBindingsVisibleBefore(tokens, index),
        )
    }

    private fun inlineIncludeArguments(tokens: List<GradleToken>, start: Int): List<String> {
        val arguments = mutableListOf<String>()
        var cursor = start
        while (cursor < tokens.size) {
            val token = tokens[cursor]
            when {
                token.kind == GradleTokenKind.STRING -> arguments += token.text
                token.kind == GradleTokenKind.NEWLINE ||
                    token.isSymbol(";") ||
                    token.isSymbol("}") -> return arguments
                token.isSymbol(",") -> {}
                else -> return arguments
            }
            cursor += 1
        }
        return arguments
    }

    private fun parenthesizedIncludeArguments(tokens: List<GradleToken>, open: Int): List<String> {
        val close = matchingCloseParenIndex(tokens, open) ?: return emptyList()
        val arguments = mutableListOf<String>()
        var cursor = nextCodeTokenIndex(tokens, open + 1)
        while (cursor != null && cursor < close) {
            if (tokens[cursor].kind == GradleTokenKind.STRING) {
                arguments += tokens[cursor].text
            }
            cursor = nextCodeTokenIndex(tokens, cursor + 1)
        }
        return arguments
    }

    private fun includedGradleProjectDirectory(argument: String): Path? {
        val normalized = argument.trim().removePrefix(":").replace(':', '/')
        if (normalized.isEmpty()) {
            return null
        }
        return Path.of(normalized)
    }

    private fun normalizeGradleProjectPath(argument: String): String? {
        val trimmed = argument.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return if (trimmed.startsWith(":")) trimmed else ":$trimmed"
    }

    private data class SettingsDefinedVersionCatalogBody(
        val catalogName: String,
        val bodyClose: Int,
        val bodyOpen: Int,
    )

    private fun settingsDefinedVersionCatalogBody(
        tokens: List<GradleToken>,
        index: Int,
    ): SettingsDefinedVersionCatalogBody? =
        explicitSettingsDefinedVersionCatalogBody(tokens, index)
            ?: groovyShorthandVersionCatalogBody(tokens, index)

    private fun explicitSettingsDefinedVersionCatalogBody(
        tokens: List<GradleToken>,
        index: Int,
    ): SettingsDefinedVersionCatalogBody? {
        if (!tokens.getOrNull(index).isIdentifier("create")) {
            return null
        }
        val open = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (!tokens[open].isSymbol("(")) {
            return null
        }
        val catalogNameIndex = nextCodeTokenIndex(tokens, open + 1) ?: return null
        if (tokens[catalogNameIndex].kind != GradleTokenKind.STRING) {
            return null
        }
        val close = matchingCloseParenIndex(tokens, open) ?: return null
        val bodyOpen = nextCodeTokenIndex(tokens, close + 1) ?: return null
        if (!tokens[bodyOpen].isSymbol("{")) {
            return null
        }
        val bodyClose = matchingCloseBraceIndex(tokens, bodyOpen) ?: return null
        return SettingsDefinedVersionCatalogBody(
            catalogName = tokens[catalogNameIndex].text.lowercase(),
            bodyClose = bodyClose,
            bodyOpen = bodyOpen,
        )
    }

    private fun groovyShorthandVersionCatalogBody(
        tokens: List<GradleToken>,
        index: Int,
    ): SettingsDefinedVersionCatalogBody? {
        val catalogNameToken = tokens.getOrNull(index) ?: return null
        if (catalogNameToken.kind != GradleTokenKind.IDENTIFIER || catalogNameToken.isIdentifier("create")) {
            return null
        }
        val bodyOpen = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (!tokens[bodyOpen].isSymbol("{")) {
            return null
        }
        val parentBodyOpen = containingBodyOpenIndex(tokens, index) ?: return null
        val parentNameIndex = previousCodeTokenIndex(tokens, parentBodyOpen - 1) ?: return null
        if (!tokens[parentNameIndex].isIdentifier("versionCatalogs")) {
            return null
        }
        val bodyClose = matchingCloseBraceIndex(tokens, bodyOpen) ?: return null
        return SettingsDefinedVersionCatalogBody(
            catalogName = catalogNameToken.text.lowercase(),
            bodyClose = bodyClose,
            bodyOpen = bodyOpen,
        )
    }

    private fun settingsDefinedVersionCatalogSources(
        projectRoot: Path,
        tokens: List<GradleToken>,
        index: Int,
    ): Set<GradleVersionCatalogSource> {
        val catalogBody = settingsDefinedVersionCatalogBody(tokens, index) ?: return emptySet()
        return (catalogBody.bodyOpen + 1 until catalogBody.bodyClose)
            .flatMap { cursor ->
                versionCatalogSourcesFromFromCall(
                    projectRoot = projectRoot,
                    catalogName = catalogBody.catalogName,
                    tokens = tokens,
                    index = cursor,
                    endExclusive = catalogBody.bodyClose,
                    stringBindings = stringBindingsVisibleBefore(tokens, cursor),
                )
            }
            .toSet()
    }

    private fun settingsDefinedVersionCatalogPluginAliases(
        tokens: List<GradleToken>,
        index: Int,
    ): Set<VersionCatalogPluginAliasDefinition> {
        val catalogBody = settingsDefinedVersionCatalogBody(tokens, index) ?: return emptySet()
        return (catalogBody.bodyOpen + 1 until catalogBody.bodyClose)
            .flatMap { cursor ->
                versionCatalogAliasDefinitionsFromPluginCall(
                    catalogName = catalogBody.catalogName,
                    tokens = tokens,
                    index = cursor,
                    stringBindings = stringBindingsVisibleBefore(tokens, cursor),
                )
            }
            .toSet()
    }

    private fun versionCatalogAliasDefinitionsFromPluginCall(
        catalogName: String,
        tokens: List<GradleToken>,
        index: Int,
        stringBindings: Map<String, String>,
    ): List<VersionCatalogPluginAliasDefinition> {
        if (!tokens.getOrNull(index).isIdentifier("plugin")) {
            return emptyList()
        }
        val arguments =
            resolvedStringCallArguments(
                tokens = tokens,
                index = index,
                stringBindings = stringBindings,
            )
        if (arguments.size < 2) {
            return emptyList()
        }
        return listOf(
            VersionCatalogPluginAliasDefinition(
                catalogName = catalogName,
                accessor = aliasToGradleAccessor(arguments[0].lowercase()),
                gradlePluginId = arguments[1].lowercase(),
            )
        )
    }

    private fun versionCatalogSourcesFromFromCall(
        projectRoot: Path,
        catalogName: String,
        tokens: List<GradleToken>,
        index: Int,
        endExclusive: Int,
        stringBindings: Map<String, String>,
    ): List<GradleVersionCatalogSource> {
        if (!tokens.getOrNull(index).isIdentifier("from")) {
            return emptyList()
        }
        val open = nextCodeTokenIndex(tokens, index + 1) ?: return emptyList()
        if (!tokens[open].isSymbol("(")) {
            return emptyList()
        }
        val close = matchingCloseParenIndex(tokens, open) ?: return emptyList()
        if (close > endExclusive) {
            return emptyList()
        }
        return (open + 1 until close)
            .flatMap { cursor ->
                versionCatalogFilesFromFilesCall(
                    projectRoot = projectRoot,
                    catalogName = catalogName,
                    tokens = tokens,
                    index = cursor,
                    endExclusive = close,
                    stringBindings = stringBindings,
                )
            }
            .distinct()
    }

    private fun versionCatalogFilesFromFilesCall(
        projectRoot: Path,
        catalogName: String,
        tokens: List<GradleToken>,
        index: Int,
        endExclusive: Int,
        stringBindings: Map<String, String>,
    ): List<GradleVersionCatalogSource> {
        if (!tokens.getOrNull(index).isIdentifier("files")) {
            return emptyList()
        }
        val open = nextCodeTokenIndex(tokens, index + 1) ?: return emptyList()
        if (!tokens[open].isSymbol("(")) {
            return emptyList()
        }
        val close = matchingCloseParenIndex(tokens, open) ?: return emptyList()
        if (close > endExclusive) {
            return emptyList()
        }
        return (open + 1 until close)
            .mapNotNull { cursor -> resolveStringToken(tokens.getOrNull(cursor), stringBindings) }
            .filter { filePath -> filePath.endsWith(".versions.toml") }
            .map { filePath ->
                GradleVersionCatalogSource(
                    catalogName = catalogName,
                    file = projectRoot.resolve(filePath).normalize(),
                )
            }
    }

    private fun tomlSectionContent(content: String, sectionName: String): String? {
        val sectionHeader = "[$sectionName]"
        var inSection = false
        val sectionLines = mutableListOf<String>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                if (inSection) {
                    return sectionLines.joinToString(separator = "\n")
                }
                inSection = line == sectionHeader
                return@forEach
            }
            if (inSection) {
                sectionLines += rawLine
            }
        }
        return if (inSection) sectionLines.joinToString(separator = "\n") else null
    }

    private fun stringBindingsVisibleBefore(
        tokens: List<GradleToken>,
        endExclusive: Int,
    ): Map<String, String> {
        val bindings = mutableMapOf<String, String>()
        var index = 0
        while (index < endExclusive) {
            val binding =
                stringBindingAt(
                    tokens = tokens,
                    index = index,
                    endExclusive = endExclusive,
                    existingBindings = bindings,
                )
            if (binding != null) {
                bindings[binding.first] = binding.second
            }
            index += 1
        }
        return bindings
    }

    private fun stringBindingAt(
        tokens: List<GradleToken>,
        index: Int,
        endExclusive: Int,
        existingBindings: Map<String, String>,
    ): Pair<String, String>? {
        val bindingKeywordIndex =
            when {
                tokens.getOrNull(index).isIdentifier("const") -> nextCodeTokenIndex(tokens, index + 1)
                tokens.getOrNull(index).isIdentifier("val") ||
                    tokens.getOrNull(index).isIdentifier("var") -> index
                else -> null
            } ?: return null
        if (
            bindingKeywordIndex >= endExclusive ||
                (!tokens[bindingKeywordIndex].isIdentifier("val") &&
                    !tokens[bindingKeywordIndex].isIdentifier("var"))
        ) {
            return null
        }
        val nameIndex = nextCodeTokenIndex(tokens, bindingKeywordIndex + 1) ?: return null
        val equalsIndex = nextCodeTokenIndex(tokens, nameIndex + 1) ?: return null
        val valueIndex = nextCodeTokenIndex(tokens, equalsIndex + 1) ?: return null
        if (
            nameIndex >= endExclusive ||
                equalsIndex >= endExclusive ||
                valueIndex >= endExclusive ||
                tokens.getOrNull(nameIndex)?.kind != GradleTokenKind.IDENTIFIER ||
                !tokens[equalsIndex].isSymbol("=")
        ) {
            return null
        }
        val value = resolveStringToken(tokens.getOrNull(valueIndex), existingBindings) ?: return null
        return tokens[nameIndex].text.lowercase() to value
    }

    private fun resolvedStringCallArguments(
        tokens: List<GradleToken>,
        index: Int,
        stringBindings: Map<String, String>,
    ): List<String> {
        val next = nextCodeTokenIndex(tokens, index + 1) ?: return emptyList()
        return when {
            tokens[next].kind == GradleTokenKind.STRING ||
                tokens[next].kind == GradleTokenKind.IDENTIFIER ->
                inlineResolvedStringArguments(tokens = tokens, start = next, stringBindings = stringBindings)
            tokens[next].isSymbol("(") ->
                parenthesizedResolvedStringArguments(
                    tokens = tokens,
                    open = next,
                    stringBindings = stringBindings,
                )
            else -> emptyList()
        }
    }

    private fun inlineResolvedStringArguments(
        tokens: List<GradleToken>,
        start: Int,
        stringBindings: Map<String, String>,
    ): List<String> {
        val arguments = mutableListOf<String>()
        var cursor = start
        while (cursor < tokens.size) {
            val token = tokens[cursor]
            when {
                token.kind == GradleTokenKind.STRING || token.kind == GradleTokenKind.IDENTIFIER ->
                    resolveStringToken(token, stringBindings)?.let(arguments::add) ?: return arguments
                token.kind == GradleTokenKind.NEWLINE ||
                    token.isSymbol(";") ||
                    token.isSymbol("}") -> return arguments
                token.isSymbol(",") -> {}
                else -> return arguments
            }
            cursor += 1
        }
        return arguments
    }

    private fun parenthesizedResolvedStringArguments(
        tokens: List<GradleToken>,
        open: Int,
        stringBindings: Map<String, String>,
    ): List<String> {
        val close = matchingCloseParenIndex(tokens, open) ?: return emptyList()
        val arguments = mutableListOf<String>()
        var cursor = nextCodeTokenIndex(tokens, open + 1)
        while (cursor != null && cursor < close) {
            resolveStringToken(tokens.getOrNull(cursor), stringBindings)?.let(arguments::add)
            cursor = nextCodeTokenIndex(tokens, cursor + 1)
        }
        return arguments
    }

    private fun resolveStringToken(
        token: GradleToken?,
        stringBindings: Map<String, String>,
    ): String? =
        when (token?.kind) {
            GradleTokenKind.STRING -> token.text
            GradleTokenKind.IDENTIFIER -> stringBindings[token.text.lowercase()]
            else -> null
        }

    private fun parseGradleBuildScriptApplications(
        tokens: List<GradleToken>
    ): ParsedGradleBuildScriptApplications {
        val directPluginIds = mutableSetOf<String>()
        val versionCatalogAliases = mutableSetOf<VersionCatalogPluginAlias>()
        tokens.indices.forEach { index ->
            directGradlePluginApplication(tokens, index)?.let { application ->
                if (!hasApplyFalseBeforeStatementBoundary(tokens, application.endExclusive)) {
                    directPluginIds += application.gradlePluginId
                }
            }
            versionCatalogAliasApplication(tokens, index)?.let { application ->
                if (!hasApplyFalseBeforeStatementBoundary(tokens, application.endExclusive)) {
                    versionCatalogAliases += application.alias
                }
            }
        }
        return ParsedGradleBuildScriptApplications(
            directPluginIds = directPluginIds,
            versionCatalogAliases = versionCatalogAliases,
        )
    }

    private fun isGradlePluginAliasReference(
        content: String,
        versionCatalogAliases: Set<VersionCatalogPluginAlias>,
    ): Boolean {
        if (versionCatalogAliases.isEmpty()) {
            return false
        }
        return parseGradleBuildScriptApplications(tokenizeGradleScript(content))
            .versionCatalogAliases
            .any(versionCatalogAliases::contains)
    }

    private fun aliasToGradleAccessor(alias: String): String =
        alias.replace('-', '.').replace('_', '.')

    private fun hasDirectGradlePluginApplication(
        tokens: List<GradleToken>,
        gradlePluginId: String,
    ): Boolean =
        parseGradleBuildScriptApplications(tokens).directPluginIds.contains(gradlePluginId.lowercase())

    private fun directGradlePluginApplication(
        tokens: List<GradleToken>,
        index: Int,
    ): DirectGradlePluginApplication? =
        pluginIdFunctionCall(tokens, index, "id")
            ?: pluginManagerApplyCall(tokens, index)
            ?: applyPluginCall(tokens, index)

    private fun pluginIdFunctionCall(
        tokens: List<GradleToken>,
        index: Int,
        functionName: String,
    ): DirectGradlePluginApplication? {
        if (!tokens.getOrNull(index).isIdentifier(functionName)) {
            return null
        }
        val next = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (tokens[next].kind == GradleTokenKind.STRING) {
            return DirectGradlePluginApplication(
                gradlePluginId = tokens[next].text.lowercase(),
                endExclusive = next + 1,
            )
        }
        if (!tokens[next].isSymbol("(")) {
            return null
        }
        val argument = nextCodeTokenIndex(tokens, next + 1) ?: return null
        val close = nextCodeTokenIndex(tokens, argument + 1) ?: return null
        if (tokens[argument].kind != GradleTokenKind.STRING || !tokens[close].isSymbol(")")) {
            return null
        }
        return DirectGradlePluginApplication(
            gradlePluginId = tokens[argument].text.lowercase(),
            endExclusive = close + 1,
        )
    }

    private fun pluginManagerApplyCall(
        tokens: List<GradleToken>,
        index: Int,
    ): DirectGradlePluginApplication? {
        if (!tokens.getOrNull(index).isIdentifier("pluginManager")) {
            return null
        }
        val dot = nextCodeTokenIndex(tokens, index + 1) ?: return null
        val apply = nextCodeTokenIndex(tokens, dot + 1) ?: return null
        if (!tokens[dot].isSymbol(".") || !tokens[apply].isIdentifier("apply")) {
            return null
        }
        return pluginIdFunctionCall(tokens, apply, "apply")
    }

    private fun applyPluginCall(
        tokens: List<GradleToken>,
        index: Int,
    ): DirectGradlePluginApplication? {
        if (!tokens.getOrNull(index).isIdentifier("apply")) {
            return null
        }
        val next = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (tokens[next].kind == GradleTokenKind.STRING) {
            return DirectGradlePluginApplication(
                gradlePluginId = tokens[next].text.lowercase(),
                endExclusive = next + 1,
            )
        }
        if (tokens[next].isIdentifier("plugin")) {
            val colon = nextCodeTokenIndex(tokens, next + 1) ?: return null
            val value = nextCodeTokenIndex(tokens, colon + 1) ?: return null
            if (tokens[colon].isSymbol(":") && tokens[value].kind == GradleTokenKind.STRING) {
                return DirectGradlePluginApplication(
                    gradlePluginId = tokens[value].text.lowercase(),
                    endExclusive = value + 1,
                )
            }
            return null
        }
        if (!tokens[next].isSymbol("(")) {
            return null
        }
        val close = matchingCloseParenIndex(tokens, next) ?: return null
        val firstArgument = nextCodeTokenIndex(tokens, next + 1)
        if (
            firstArgument != null &&
                firstArgument < close &&
                tokens[firstArgument].kind == GradleTokenKind.STRING
        ) {
            return DirectGradlePluginApplication(
                gradlePluginId = tokens[firstArgument].text.lowercase(),
                endExclusive = close + 1,
            )
        }
        var cursor = next + 1
        while (cursor < close) {
            if (tokens[cursor].isIdentifier("plugin")) {
                val equals = nextCodeTokenIndex(tokens, cursor + 1)
                val value = equals?.let { nextCodeTokenIndex(tokens, it + 1) }
                if (
                    equals != null &&
                        value != null &&
                        value < close &&
                        (tokens[equals].isSymbol("=") || tokens[equals].isSymbol(":")) &&
                        tokens[value].kind == GradleTokenKind.STRING
                ) {
                    return DirectGradlePluginApplication(
                        gradlePluginId = tokens[value].text.lowercase(),
                        endExclusive = close + 1,
                    )
                }
            }
            cursor += 1
        }
        return null
    }

    private fun hasAppliedVersionCatalogAlias(
        tokens: List<GradleToken>,
        versionCatalogAliases: Set<VersionCatalogPluginAlias>,
    ): Boolean =
        parseGradleBuildScriptApplications(tokens).versionCatalogAliases.any(versionCatalogAliases::contains)

    private fun versionCatalogAliasApplication(
        tokens: List<GradleToken>,
        index: Int,
    ): VersionCatalogAliasApplication? {
        if (!tokens.getOrNull(index).isIdentifier("alias")) {
            return null
        }
        val open = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (!tokens[open].isSymbol("(")) {
            return null
        }
        val close = matchingCloseParenIndex(tokens, open) ?: return null
        val alias = versionCatalogAliasAccessor(tokens, open + 1, close) ?: return null
        return VersionCatalogAliasApplication(alias = alias, endExclusive = close + 1)
    }

    private fun versionCatalogAliasAccessor(
        tokens: List<GradleToken>,
        start: Int,
        endExclusive: Int,
    ): VersionCatalogPluginAlias? {
        var cursor = nextCodeTokenIndex(tokens, start) ?: return null
        if (cursor >= endExclusive || tokens[cursor].kind != GradleTokenKind.IDENTIFIER) {
            return null
        }
        val catalogName = tokens[cursor].text.lowercase()
        cursor = nextCodeTokenIndex(tokens, cursor + 1) ?: return null
        if (cursor >= endExclusive || !tokens[cursor].isSymbol(".")) {
            return null
        }
        cursor = nextCodeTokenIndex(tokens, cursor + 1) ?: return null
        if (cursor >= endExclusive || !tokens[cursor].isIdentifier("plugins")) {
            return null
        }
        cursor = nextCodeTokenIndex(tokens, cursor + 1) ?: return null
        val segments = mutableListOf<String>()
        while (cursor < endExclusive && tokens[cursor].isSymbol(".")) {
            val segmentIndex = nextCodeTokenIndex(tokens, cursor + 1) ?: break
            if (
                segmentIndex >= endExclusive ||
                    tokens[segmentIndex].kind != GradleTokenKind.IDENTIFIER
            ) {
                break
            }
            val segment = tokens[segmentIndex].text.lowercase()
            if (segment == "get") {
                break
            }
            segments += segment
            cursor = nextCodeTokenIndex(tokens, segmentIndex + 1) ?: break
        }
        return segments
            .takeIf { it.isNotEmpty() }
            ?.joinToString(".")
            ?.let { accessor ->
                VersionCatalogPluginAlias(catalogName = catalogName, accessor = accessor)
            }
    }

    private fun hasApplyFalseBeforeStatementBoundary(
        tokens: List<GradleToken>,
        start: Int,
    ): Boolean {
        var cursor = nextCodeTokenIndex(tokens, start) ?: return false
        while (cursor < tokens.size) {
            when {
                tokens[cursor].isSymbol(";") || tokens[cursor].isSymbol("}") -> return false
                tokens[cursor].isIdentifier("apply") ->
                    return applyFalseCallEnd(tokens, cursor) != null
                tokens[cursor].isIdentifier("version") -> {
                    cursor = versionSuffixEnd(tokens, cursor) ?: return false
                }
                tokens[cursor].isSymbol(".") -> {
                    val identifier = nextCodeTokenIndex(tokens, cursor + 1) ?: return false
                    when {
                        tokens[identifier].isIdentifier("apply") ->
                            return applyFalseCallEnd(tokens, identifier) != null
                        tokens[identifier].isIdentifier("version") -> {
                            cursor = versionSuffixEnd(tokens, identifier) ?: return false
                        }
                        else -> return false
                    }
                }
                else -> return false
            }
            cursor = nextCodeTokenIndex(tokens, cursor) ?: return false
        }
        return false
    }

    private fun applyFalseCallEnd(tokens: List<GradleToken>, index: Int): Int? {
        if (!tokens.getOrNull(index).isIdentifier("apply")) {
            return null
        }
        val next = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (tokens[next].isIdentifier("false")) {
            return next + 1
        }
        if (!tokens[next].isSymbol("(")) {
            return null
        }
        val argument = nextCodeTokenIndex(tokens, next + 1) ?: return null
        val close = nextCodeTokenIndex(tokens, argument + 1) ?: return null
        if (!tokens[argument].isIdentifier("false") || !tokens[close].isSymbol(")")) {
            return null
        }
        return close + 1
    }

    private fun versionSuffixEnd(tokens: List<GradleToken>, index: Int): Int? {
        if (!tokens.getOrNull(index).isIdentifier("version")) {
            return null
        }
        val next = nextCodeTokenIndex(tokens, index + 1) ?: return null
        if (tokens[next].kind == GradleTokenKind.STRING) {
            return next + 1
        }
        if (!tokens[next].isSymbol("(")) {
            return null
        }
        val close = matchingCloseParenIndex(tokens, next) ?: return null
        return close + 1
    }

    private fun matchingCloseParenIndex(tokens: List<GradleToken>, openIndex: Int): Int? {
        var depth = 0
        for (index in openIndex until tokens.size) {
            when {
                tokens[index].isSymbol("(") -> depth += 1
                tokens[index].isSymbol(")") -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun matchingCloseBraceIndex(tokens: List<GradleToken>, openIndex: Int): Int? {
        var depth = 0
        for (index in openIndex until tokens.size) {
            when {
                tokens[index].isSymbol("{") -> depth += 1
                tokens[index].isSymbol("}") -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun nextCodeTokenIndex(tokens: List<GradleToken>, start: Int): Int? {
        var cursor = start
        while (cursor < tokens.size && tokens[cursor].kind == GradleTokenKind.NEWLINE) {
            cursor += 1
        }
        return cursor.takeIf { it < tokens.size }
    }

    private fun previousCodeTokenIndex(tokens: List<GradleToken>, start: Int): Int? {
        var cursor = start
        while (cursor >= 0 && tokens[cursor].kind == GradleTokenKind.NEWLINE) {
            cursor -= 1
        }
        return cursor.takeIf { it >= 0 }
    }

    private fun containingBodyOpenIndex(tokens: List<GradleToken>, index: Int): Int? {
        var depth = 0
        for (cursor in index - 1 downTo 0) {
            when {
                tokens[cursor].isSymbol("}") -> depth += 1
                tokens[cursor].isSymbol("{") -> {
                    if (depth == 0) {
                        return cursor
                    }
                    depth -= 1
                }
            }
        }
        return null
    }

    private fun GradleToken?.isIdentifier(value: String): Boolean =
        this?.kind == GradleTokenKind.IDENTIFIER && text.equals(value, ignoreCase = true)

    private fun GradleToken?.isString(value: String): Boolean =
        this?.kind == GradleTokenKind.STRING && text == value

    private fun GradleToken?.isSymbol(value: String): Boolean =
        this?.kind == GradleTokenKind.SYMBOL && text == value

    private fun tokenizeGradleScript(content: String): List<GradleToken> {
        val tokens = mutableListOf<GradleToken>()
        var index = 0
        while (index < content.length) {
            when {
                content[index] == '\r' || content[index] == '\n' -> {
                    if (
                        content[index] == '\r' &&
                            index + 1 < content.length &&
                            content[index + 1] == '\n'
                    ) {
                        index += 1
                    }
                    tokens += GradleToken(GradleTokenKind.NEWLINE, "\n")
                    index += 1
                }
                content[index].isWhitespace() -> index += 1
                content.startsWith("//", index) -> {
                    index += 2
                    while (
                        index < content.length && content[index] != '\n' && content[index] != '\r'
                    ) {
                        index += 1
                    }
                }
                content[index] == '#' -> {
                    index += 1
                    while (
                        index < content.length && content[index] != '\n' && content[index] != '\r'
                    ) {
                        index += 1
                    }
                }
                content.startsWith("/*", index) -> {
                    index += 2
                    while (index < content.length && !content.startsWith("*/", index)) {
                        if (content[index] == '\n' || content[index] == '\r') {
                            tokens += GradleToken(GradleTokenKind.NEWLINE, "\n")
                            if (
                                content[index] == '\r' &&
                                    index + 1 < content.length &&
                                    content[index + 1] == '\n'
                            ) {
                                index += 1
                            }
                        }
                        index += 1
                    }
                    if (index < content.length) {
                        index += 2
                    }
                }
                content.startsWith("\"\"\"", index) || content.startsWith("'''", index) -> {
                    val delimiter = content.substring(index, index + 3)
                    val value = StringBuilder()
                    index += 3
                    while (index < content.length && !content.startsWith(delimiter, index)) {
                        value.append(content[index])
                        index += 1
                    }
                    if (index < content.length) {
                        index += 3
                    }
                    tokens += GradleToken(GradleTokenKind.STRING, value.toString())
                }
                content[index] == '"' || content[index] == '\'' -> {
                    val quote = content[index]
                    val value = StringBuilder()
                    index += 1
                    while (index < content.length) {
                        val current = content[index]
                        if (current == '\\' && index + 1 < content.length) {
                            value.append(content[index + 1])
                            index += 2
                            continue
                        }
                        if (current == quote) {
                            index += 1
                            break
                        }
                        value.append(current)
                        index += 1
                    }
                    tokens += GradleToken(GradleTokenKind.STRING, value.toString())
                }
                isIdentifierStart(content[index]) -> {
                    val start = index
                    index += 1
                    while (index < content.length && isIdentifierPart(content[index])) {
                        index += 1
                    }
                    tokens +=
                        GradleToken(GradleTokenKind.IDENTIFIER, content.substring(start, index))
                }
                else -> {
                    tokens += GradleToken(GradleTokenKind.SYMBOL, content[index].toString())
                    index += 1
                }
            }
        }
        return tokens
    }

    private fun isIdentifierStart(char: Char): Boolean =
        char == '_' || char == '$' || char.isLetter()

    private fun isIdentifierPart(char: Char): Boolean =
        isIdentifierStart(char) || char.isDigit() || char == '-'

    private fun stripCommentsPreservingStrings(content: String): String {
        val result = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            when {
                content.startsWith("//", index) -> {
                    index += 2
                    while (index < content.length && content[index] != '\n') {
                        index += 1
                    }
                }
                content[index] == '#' -> {
                    index += 1
                    while (index < content.length && content[index] != '\n') {
                        index += 1
                    }
                }
                content.startsWith("/*", index) -> {
                    index += 2
                    while (index < content.length && !content.startsWith("*/", index)) {
                        index += 1
                    }
                    if (index < content.length) {
                        index += 2
                    }
                }
                content.startsWith("\"\"\"", index) -> {
                    result.append("\"\"\"")
                    index += 3
                    while (index < content.length && !content.startsWith("\"\"\"", index)) {
                        result.append(content[index])
                        index += 1
                    }
                    if (index < content.length) {
                        result.append("\"\"\"")
                        index += 3
                    }
                }
                content.startsWith("'''", index) -> {
                    result.append("'''")
                    index += 3
                    while (index < content.length && !content.startsWith("'''", index)) {
                        result.append(content[index])
                        index += 1
                    }
                    if (index < content.length) {
                        result.append("'''")
                        index += 3
                    }
                }
                content[index] == '"' || content[index] == '\'' -> {
                    val quote = content[index]
                    result.append(quote)
                    index += 1
                    while (index < content.length) {
                        val current = content[index]
                        result.append(current)
                        index += 1
                        if (current == '\\' && index < content.length) {
                            result.append(content[index])
                            index += 1
                            continue
                        }
                        if (current == quote) {
                            break
                        }
                    }
                }
                else -> {
                    result.append(content[index])
                    index += 1
                }
            }
        }
        return result.toString()
    }
}
