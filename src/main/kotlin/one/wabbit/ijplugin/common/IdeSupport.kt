// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.ijplugin.common

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationType
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

enum class IdeSupportProjectState {
    NOT_NEEDED,
    WAITING_FOR_TRUST,
    WAITING_FOR_IMPORT,
    IMPORT_IN_FLIGHT,
    ACTIVE,
    FAILED,
}

enum class IdeSupportActivationState {
    NOT_NEEDED,
    WAITING_FOR_TRUST,
    WAITING_FOR_GRADLE_IMPORT,
    ALREADY_ENABLED,
    ENABLED_NOW,
    REGISTRY_UNAVAILABLE,
    FAILED_TO_ENABLE,
}

enum class ExternalCompilerPluginRegistryState {
    BLOCKING,
    ALREADY_ALLOWED,
    UNAVAILABLE,
}

data class IdeSupportResult<Scan>(
    val scan: Scan,
    val projectTrusted: Boolean,
    val registryState: ExternalCompilerPluginRegistryState,
    val registryAlreadyEnabledForExternalPlugins: Boolean,
    val registryUpdated: Boolean,
    val gradleImportRequested: Boolean,
    val activationState: IdeSupportActivationState,
)

data class IdeSupportMessages(
    val noMatchesTitle: String,
    val noMatchesContent: String,
    val waitingForTrustTitle: String,
    val waitingForTrustContent: String,
    val activeTitle: String,
    val waitingForGradleImportTitle: String = activeTitle,
    val failedTitle: String,
)

enum class ExternalCompilerPluginSessionRegistrationResult {
    CHANGED_VALUE,
    REGISTERED_WITHOUT_CHANGE,
    SKIPPED_UNAVAILABLE,
    FAILED,
}

object ExternalCompilerPluginRegistrySupport {
    private val lock = Any()
    private val activeProjectsByRegistryKey = mutableMapOf<String, MutableSet<Project>>()
    private val originalValuesByRegistryKey = mutableMapOf<String, Boolean>()

    fun registryState(
        read: () -> Boolean,
        logFailure: (Throwable) -> Unit = {},
    ): ExternalCompilerPluginRegistryState =
        runCatching { read() }
            .map { allowsOnlyBundledPlugins ->
                if (allowsOnlyBundledPlugins) {
                    ExternalCompilerPluginRegistryState.BLOCKING
                } else {
                    ExternalCompilerPluginRegistryState.ALREADY_ALLOWED
                }
            }
            .getOrElse { error ->
                logFailure(error)
                ExternalCompilerPluginRegistryState.UNAVAILABLE
            }

    fun enableExternalPluginsForProjectSession(
        registryKey: String,
        project: Project,
        read: () -> Boolean,
        update: (Boolean) -> Unit,
        logFailure: (Throwable) -> Unit = {},
        registerDisposer: (Project, () -> Unit) -> Unit = { disposableProject, release ->
            Disposer.register(disposableProject, release)
        },
    ): ExternalCompilerPluginSessionRegistrationResult {
        return runCatching {
                val result: ExternalCompilerPluginSessionRegistrationResult =
                    synchronized(lock) {
                        var currentActiveProjects = activeProjectsByRegistryKey[registryKey]
                        if (currentActiveProjects != null) {
                            pruneDisposedProjectsLocked(currentActiveProjects)
                            if (currentActiveProjects.isEmpty()) {
                                activeProjectsByRegistryKey.remove(registryKey)
                                currentActiveProjects = null
                            }
                        }
                        if (currentActiveProjects != null && project in currentActiveProjects) {
                            ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                        } else {
                            val hadStoredOriginalValue = registryKey in originalValuesByRegistryKey
                            val originalValue = originalValuesByRegistryKey[registryKey] ?: read()
                            val shouldUpdateRegistry =
                                currentActiveProjects == null &&
                                    !hadStoredOriginalValue &&
                                    originalValue
                            var registryUpdated = false

                            try {
                                if (shouldUpdateRegistry) {
                                    update(false)
                                    registryUpdated = true
                                }
                                registerDisposer(project) {
                                    releaseExternalPluginsForProjectSession(
                                        registryKey = registryKey,
                                        project = project,
                                        update = update,
                                        logFailure = logFailure,
                                    )
                                }

                                val updatedActiveProjects =
                                    currentActiveProjects
                                        ?: Collections.newSetFromMap(
                                            IdentityHashMap<Project, Boolean>()
                                        )
                                updatedActiveProjects += project
                                activeProjectsByRegistryKey[registryKey] = updatedActiveProjects
                                if (currentActiveProjects == null && !hadStoredOriginalValue) {
                                    originalValuesByRegistryKey[registryKey] = originalValue
                                }

                                if (shouldUpdateRegistry) {
                                    ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE
                                } else {
                                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                                }
                            } catch (error: Throwable) {
                                if (registryUpdated) {
                                    runCatching { update(originalValue) }
                                        .exceptionOrNull()
                                        ?.let(error::addSuppressed)
                                }
                                throw error
                            }
                        }
                    }
                result
            }
            .getOrElse { error ->
                logFailure(error)
                ExternalCompilerPluginSessionRegistrationResult.FAILED
            }
    }

    private fun releaseExternalPluginsForProjectSession(
        registryKey: String,
        project: Project,
        update: (Boolean) -> Unit,
        logFailure: (Throwable) -> Unit,
    ) {
        runCatching {
                synchronized(lock) {
                    val activeProjects = activeProjectsByRegistryKey[registryKey] ?: return
                    pruneDisposedProjectsLocked(activeProjects, keepProject = project)
                    if (project !in activeProjects) {
                        return
                    }
                    if (activeProjects.isEmpty()) {
                        activeProjectsByRegistryKey.remove(registryKey)
                        return
                    }
                    if (activeProjects.size == 1) {
                        originalValuesByRegistryKey[registryKey]?.let(update)
                        activeProjects.remove(project)
                        if (activeProjects.isEmpty()) {
                            activeProjectsByRegistryKey.remove(registryKey)
                            originalValuesByRegistryKey.remove(registryKey)
                        }
                        return
                    }
                    activeProjects.remove(project)
                }
            }
            .onFailure(logFailure)
    }

    private fun pruneDisposedProjectsLocked(
        activeProjects: MutableSet<Project>,
        keepProject: Project? = null,
    ) {
        activeProjects.removeAll { trackedProject ->
            trackedProject !== keepProject && trackedProject.isDisposed
        }
    }
}

private data class IdeSupportProjectSnapshot(
    val state: IdeSupportProjectState,
    val lastRequestedGradleImportSignature: String?,
    val lastImportedGradleDetectionSignature: String?,
)

class IdeSupportProjectStateTracker {
    private val snapshots = WeakHashMap<Any, IdeSupportProjectSnapshot>()

    fun currentState(sessionKey: Any): IdeSupportProjectState? =
        synchronized(snapshots) { snapshots[sessionKey]?.state }

    fun currentLastRequestedGradleImportSignature(sessionKey: Any): String? =
        synchronized(snapshots) { snapshots[sessionKey]?.lastRequestedGradleImportSignature }

    fun currentLastImportedGradleDetectionSignature(sessionKey: Any): String? =
        synchronized(snapshots) { snapshots[sessionKey]?.lastImportedGradleDetectionSignature }

    fun markImportFinished(sessionKey: Any) {
        synchronized(snapshots) {
            val snapshot = snapshots[sessionKey] ?: return
            if (snapshot.state == IdeSupportProjectState.IMPORT_IN_FLIGHT) {
                snapshots[sessionKey] =
                    snapshot.copy(state = IdeSupportProjectState.WAITING_FOR_IMPORT)
            }
        }
    }

    fun transitionTo(
        sessionKey: Any,
        state: IdeSupportProjectState,
        lastRequestedGradleImportSignature: String?,
        lastImportedGradleDetectionSignature: String?,
    ) {
        synchronized(snapshots) {
            snapshots[sessionKey] =
                IdeSupportProjectSnapshot(
                    state = state,
                    lastRequestedGradleImportSignature = lastRequestedGradleImportSignature,
                    lastImportedGradleDetectionSignature = lastImportedGradleDetectionSignature,
                )
        }
    }
}

fun buildIdeSupportOwners(scan: CompilerPluginScan): List<String> = buildList {
    scan.projectLevelMatch?.let { add("project settings") }
    addAll(scan.moduleMatches.map { match -> "module ${match.ownerName}" })
    if (scan.gradleBuildFiles.isNotEmpty()) {
        add("Gradle build files")
    }
}

fun gradleImportPaths(scan: CompilerPluginScan, projectBasePath: String?): List<String> =
    scan.gradleImportPaths.ifEmpty { listOfNotNull(projectBasePath) }.distinct()

fun requestExternalSystemImport(
    project: Project,
    externalProjectPaths: List<String>,
    projectSystemId: ProjectSystemId,
    logFailure: (String, Throwable) -> Unit = { _, _ -> },
): Boolean {
    return externalProjectPaths.distinct().fold(false) { requestedAny, externalProjectPath ->
        val requested =
            runCatching {
                    ExternalSystemUtil.requestImport(project, externalProjectPath, projectSystemId)
                }
                .onFailure { throwable -> logFailure(externalProjectPath, throwable) }
                .isSuccess
        requestedAny || requested
    }
}

fun restartAnalysis(project: Project, reason: String) {
    DaemonCodeAnalyzer.getInstance(project).restart(reason)
}

private fun gradleImportStateSignature(scan: CompilerPluginScan): String =
    buildString {
        append("detection=")
        append(scan.gradleDetectionSignature.orEmpty())
        append("|files=")
        append(scan.gradleBuildFiles.sorted().joinToString(separator = ","))
        append("|paths=")
        append(scan.gradleImportPaths.sorted().joinToString(separator = ","))
    }

private fun hasImportedGradleState(
    scan: CompilerPluginScan,
    currentGradleImportSignature: String,
    previousState: IdeSupportProjectState?,
    lastRequestedGradleImportSignature: String?,
    lastImportedGradleDetectionSignature: String?,
): Boolean {
    if (!scan.hasImportedCompilerPluginMatches) {
        return false
    }
    if (lastImportedGradleDetectionSignature == currentGradleImportSignature) {
        return true
    }
    if (
        previousState == IdeSupportProjectState.WAITING_FOR_IMPORT &&
            lastRequestedGradleImportSignature == currentGradleImportSignature
    ) {
        return true
    }
    return lastImportedGradleDetectionSignature == null &&
        lastRequestedGradleImportSignature == null &&
        previousState != IdeSupportProjectState.IMPORT_IN_FLIGHT &&
        previousState != IdeSupportProjectState.WAITING_FOR_IMPORT
}

private fun requiresGradleImport(
    scan: CompilerPluginScan,
    currentGradleImportSignature: String,
    previousState: IdeSupportProjectState?,
    lastRequestedGradleImportSignature: String?,
    lastImportedGradleDetectionSignature: String?,
): Boolean {
    if (!scan.hasGradleBuildFileMatches) {
        return scan.hasImportedCompilerPluginMatches &&
            lastImportedGradleDetectionSignature != null &&
            currentGradleImportSignature != lastImportedGradleDetectionSignature
    }
    return !hasImportedGradleState(
        scan = scan,
        currentGradleImportSignature = currentGradleImportSignature,
        previousState = previousState,
        lastRequestedGradleImportSignature = lastRequestedGradleImportSignature,
        lastImportedGradleDetectionSignature = lastImportedGradleDetectionSignature,
    )
}

fun enableIdeSupport(
    scan: CompilerPluginScan,
    projectTrusted: Boolean,
    userInitiated: Boolean,
    registryState: ExternalCompilerPluginRegistryState,
    enableExternalPluginsForProjectSession: () -> ExternalCompilerPluginSessionRegistrationResult,
    requestGradleImport: (List<String>) -> Boolean = { false },
    restartAnalysis: () -> Unit = {},
    notify: (NotificationType, String, String) -> Unit,
    messages: IdeSupportMessages,
    buildEnabledMessage:
        (CompilerPluginScan, IdeSupportActivationState, Boolean, Boolean) -> String,
    buildFailedEnablementMessage: (CompilerPluginScan) -> String,
    sessionKey: Any? = null,
    projectStateTracker: IdeSupportProjectStateTracker? = null,
): IdeSupportResult<CompilerPluginScan> {
    val previousState =
        if (sessionKey != null && projectStateTracker != null) {
            projectStateTracker.currentState(sessionKey)
        } else {
            null
        }
    val previousGradleImportRequestSignature =
        if (sessionKey != null && projectStateTracker != null) {
            projectStateTracker.currentLastRequestedGradleImportSignature(sessionKey)
        } else {
            null
        }
    val previousImportedGradleDetectionSignature =
        if (sessionKey != null && projectStateTracker != null) {
            projectStateTracker.currentLastImportedGradleDetectionSignature(sessionKey)
        } else {
            null
        }
    val currentGradleImportSignature = gradleImportStateSignature(scan)
    val gradleImportRequired =
        requiresGradleImport(
            scan = scan,
            currentGradleImportSignature = currentGradleImportSignature,
            previousState = previousState,
            lastRequestedGradleImportSignature = previousGradleImportRequestSignature,
            lastImportedGradleDetectionSignature = previousImportedGradleDetectionSignature,
        )
    if (!scan.hasMatches) {
        if (userInitiated) {
            notify(NotificationType.INFORMATION, messages.noMatchesTitle, messages.noMatchesContent)
        }
        if (sessionKey != null && projectStateTracker != null) {
            projectStateTracker.transitionTo(
                sessionKey = sessionKey,
                state = IdeSupportProjectState.NOT_NEEDED,
                lastRequestedGradleImportSignature = null,
                lastImportedGradleDetectionSignature = null,
            )
        }
        return IdeSupportResult(
            scan = scan,
            projectTrusted = projectTrusted,
            registryState = registryState,
            registryAlreadyEnabledForExternalPlugins =
                registryState == ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            registryUpdated = false,
            gradleImportRequested = false,
            activationState = IdeSupportActivationState.NOT_NEEDED,
        )
    }

    if (!projectTrusted) {
        if (previousState != IdeSupportProjectState.WAITING_FOR_TRUST || userInitiated) {
            notify(
                NotificationType.WARNING,
                messages.waitingForTrustTitle,
                messages.waitingForTrustContent,
            )
        }
        if (sessionKey != null && projectStateTracker != null) {
            projectStateTracker.transitionTo(
                sessionKey = sessionKey,
                state = IdeSupportProjectState.WAITING_FOR_TRUST,
                lastRequestedGradleImportSignature = previousGradleImportRequestSignature,
                lastImportedGradleDetectionSignature = previousImportedGradleDetectionSignature,
            )
        }
        return IdeSupportResult(
            scan = scan,
            projectTrusted = false,
            registryState = registryState,
            registryAlreadyEnabledForExternalPlugins =
                registryState == ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            registryUpdated = false,
            gradleImportRequested = false,
            activationState = IdeSupportActivationState.WAITING_FOR_TRUST,
        )
    }

    val registryRegistrationResult =
        when (registryState) {
            ExternalCompilerPluginRegistryState.BLOCKING,
            ExternalCompilerPluginRegistryState.ALREADY_ALLOWED ->
                runCatching { enableExternalPluginsForProjectSession() }
                    .getOrDefault(ExternalCompilerPluginSessionRegistrationResult.FAILED)

            ExternalCompilerPluginRegistryState.UNAVAILABLE ->
                ExternalCompilerPluginSessionRegistrationResult.SKIPPED_UNAVAILABLE
        }
    val activationState =
        when (registryRegistrationResult) {
            ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE ->
                IdeSupportActivationState.ENABLED_NOW
            ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE ->
                IdeSupportActivationState.ALREADY_ENABLED
            ExternalCompilerPluginSessionRegistrationResult.SKIPPED_UNAVAILABLE ->
                IdeSupportActivationState.REGISTRY_UNAVAILABLE
            ExternalCompilerPluginSessionRegistrationResult.FAILED ->
                IdeSupportActivationState.FAILED_TO_ENABLE
        }
    val registryUpdated = activationState == IdeSupportActivationState.ENABLED_NOW
    val registryAlreadyEnabledForExternalPlugins =
        activationState == IdeSupportActivationState.ALREADY_ENABLED

    if (activationState == IdeSupportActivationState.FAILED_TO_ENABLE) {
        if (previousState != IdeSupportProjectState.FAILED || userInitiated) {
            notify(
                NotificationType.WARNING,
                messages.failedTitle,
                buildFailedEnablementMessage(scan),
            )
        }
        if (sessionKey != null && projectStateTracker != null) {
            projectStateTracker.transitionTo(
                sessionKey = sessionKey,
                state = IdeSupportProjectState.FAILED,
                lastRequestedGradleImportSignature = previousGradleImportRequestSignature,
                lastImportedGradleDetectionSignature = previousImportedGradleDetectionSignature,
            )
        }
        return IdeSupportResult(
            scan = scan,
            projectTrusted = true,
            registryState = registryState,
            registryAlreadyEnabledForExternalPlugins = false,
            registryUpdated = false,
            gradleImportRequested = false,
            activationState = IdeSupportActivationState.FAILED_TO_ENABLE,
        )
    }

    if (gradleImportRequired) {
        val shouldRequestGradleImport =
            userInitiated ||
                previousGradleImportRequestSignature == null ||
                previousGradleImportRequestSignature != currentGradleImportSignature ||
                previousState != IdeSupportProjectState.IMPORT_IN_FLIGHT &&
                    previousState != IdeSupportProjectState.WAITING_FOR_IMPORT
        val gradleImportRequested =
            if (shouldRequestGradleImport) {
                runCatching { requestGradleImport(scan.gradleImportPaths) }.getOrDefault(false)
            } else {
                false
            }
        val currentProjectState =
            when {
                gradleImportRequested -> IdeSupportProjectState.IMPORT_IN_FLIGHT
                previousState == IdeSupportProjectState.IMPORT_IN_FLIGHT ->
                    IdeSupportProjectState.IMPORT_IN_FLIGHT
                else -> IdeSupportProjectState.WAITING_FOR_IMPORT
            }
        val lastRequestedGradleImportSignature =
            if (gradleImportRequested) {
                currentGradleImportSignature
            } else {
                previousGradleImportRequestSignature
            }
        if (sessionKey != null && projectStateTracker != null) {
            projectStateTracker.transitionTo(
                sessionKey = sessionKey,
                state = currentProjectState,
                lastRequestedGradleImportSignature = lastRequestedGradleImportSignature,
                lastImportedGradleDetectionSignature = previousImportedGradleDetectionSignature,
            )
        }
        val shouldNotify =
            userInitiated ||
                currentProjectState != previousState ||
                registryUpdated ||
                gradleImportRequested
        if (shouldNotify) {
            notify(
                NotificationType.INFORMATION,
                messages.waitingForGradleImportTitle,
                buildEnabledMessage(
                    scan,
                    activationState,
                    gradleImportRequired,
                    gradleImportRequested,
                ),
            )
        }
        return IdeSupportResult(
            scan = scan,
            projectTrusted = true,
            registryState = registryState,
            registryAlreadyEnabledForExternalPlugins = registryAlreadyEnabledForExternalPlugins,
            registryUpdated = registryUpdated,
            gradleImportRequested = gradleImportRequested,
            activationState = IdeSupportActivationState.WAITING_FOR_GRADLE_IMPORT,
        )
    }

    val pluginJustBecameUsable =
        scan.hasImportedCompilerPluginMatches &&
            (previousState != IdeSupportProjectState.ACTIVE ||
                activationState == IdeSupportActivationState.ENABLED_NOW)

    if (pluginJustBecameUsable) {
        restartAnalysis()
    }

    if (sessionKey != null && projectStateTracker != null) {
        projectStateTracker.transitionTo(
            sessionKey = sessionKey,
            state = IdeSupportProjectState.ACTIVE,
            lastRequestedGradleImportSignature = null,
            lastImportedGradleDetectionSignature = currentGradleImportSignature,
        )
    }

    when {
        previousState != IdeSupportProjectState.ACTIVE ||
            activationState == IdeSupportActivationState.ENABLED_NOW ||
            userInitiated ->
            notify(
                NotificationType.INFORMATION,
                messages.activeTitle,
                buildEnabledMessage(
                    scan,
                    activationState,
                    false,
                    false,
                ),
            )
    }

    return IdeSupportResult(
        scan = scan,
        projectTrusted = true,
        registryState = registryState,
        registryAlreadyEnabledForExternalPlugins = registryAlreadyEnabledForExternalPlugins,
        registryUpdated = registryUpdated,
        gradleImportRequested = false,
        activationState = activationState,
    )
}
