// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.ijplugin.common

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.nio.file.Path

open class ConfiguredCompilerPluginDetectorSupport(
    private val support: ConfiguredCompilerPluginIdeSupport
) {
    fun scan(project: Project): CompilerPluginScan = support.detector.scan(project)

    fun matchingClasspaths(classpaths: Iterable<String>): List<String> =
        support.detector.matchingClasspaths(classpaths)

    fun isCompilerPluginPath(classpath: String): Boolean =
        support.detector.isCompilerPluginPath(classpath)

    fun matchingGradleBuildFiles(projectRoot: Path): List<String> =
        support.detector.matchingGradleBuildFiles(projectRoot)

    fun matchingGradleBuildFiles(projectRoots: Iterable<Path>): List<String> =
        support.detector.matchingGradleBuildFiles(projectRoots)

    fun matchingGradleBuildFileMatches(projectRoots: Iterable<Path>): List<GradleBuildFileMatch> =
        support.detector.matchingGradleBuildFileMatches(projectRoots)

    fun isDirectGradlePluginReference(content: String): Boolean =
        support.detector.isDirectGradlePluginReference(content)
}

open class ConfiguredIdeSupportCoordinatorSupport(
    private val support: ConfiguredCompilerPluginIdeSupport
) : ProjectIdeSupportCoordinator by support.coordinator {
    private val delegate = support.coordinator

    var gradleImportRequester: IdeSupportGradleImportRequester
        get() = delegate.gradleImportRequester
        set(value) {
            delegate.gradleImportRequester = value
        }

    var analysisRestarter: IdeSupportAnalysisRestarter
        get() = delegate.analysisRestarter
        set(value) {
            delegate.analysisRestarter = value
        }

    fun enableIfNeeded(
        scan: CompilerPluginScan,
        projectTrusted: Boolean,
        userInitiated: Boolean,
        registryState: ExternalCompilerPluginRegistryState,
        enableExternalPluginsForProjectSession:
            () -> ExternalCompilerPluginSessionRegistrationResult,
        requestGradleImport: (List<String>) -> Boolean = { false },
        restartAnalysis: () -> Unit = {},
        notify: (NotificationType, String, String) -> Unit,
        sessionKey: Any? = null,
        projectStateTracker: IdeSupportProjectStateTracker? = null,
    ): IdeSupportResult<CompilerPluginScan> =
        delegate.enableIfNeeded(
            scan = scan,
            projectTrusted = projectTrusted,
            userInitiated = userInitiated,
            registryState = registryState,
            enableExternalPluginsForProjectSession = enableExternalPluginsForProjectSession,
            requestGradleImport = requestGradleImport,
            restartAnalysis = restartAnalysis,
            notify = notify,
            sessionKey = sessionKey,
            projectStateTracker = projectStateTracker,
        )

    fun buildEnabledMessage(
        scan: CompilerPluginScan,
        activationState: IdeSupportActivationState,
    ): String =
        support.buildEnabledMessage(
            scan = scan,
            activationState = activationState,
            gradleImportRequired = false,
            gradleImportRequested = false,
        )

    fun buildGradleImportRequiredMessage(
        scan: CompilerPluginScan,
        activationState: IdeSupportActivationState,
        gradleImportRequested: Boolean,
    ): String =
        support.buildEnabledMessage(
            scan = scan,
            activationState = activationState,
            gradleImportRequired = true,
            gradleImportRequested = gradleImportRequested,
        )

    fun buildEnabledMessage(
        scan: CompilerPluginScan,
        registryUpdated: Boolean,
        gradleImportRequired: Boolean,
        gradleImportRequested: Boolean,
    ): String =
        support.buildEnabledMessage(
            scan = scan,
            activationState =
                if (registryUpdated) {
                    IdeSupportActivationState.ENABLED_NOW
                } else {
                    IdeSupportActivationState.ALREADY_ENABLED
                },
            gradleImportRequired = gradleImportRequired,
            gradleImportRequested = gradleImportRequested,
        )

    fun gradleImportPaths(scan: CompilerPluginScan, projectBasePath: String?): List<String> =
        delegate.gradleImportPaths(scan, projectBasePath)

    fun buildFailedEnablementMessage(scan: CompilerPluginScan): String =
        support.buildFailedEnablementMessage(scan)
}
