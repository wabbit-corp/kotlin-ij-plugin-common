// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.ijplugin.common

import com.intellij.openapi.project.Project
import java.nio.file.Path

const val EXTERNAL_K2_COMPILER_PLUGINS_REGISTRY_KEY =
    "kotlin.k2.only.bundled.compiler.plugins.enabled"

interface CompilerPluginDetectorFacade {
    fun scan(project: Project): CompilerPluginScan

    fun matchingClasspaths(classpaths: Iterable<String>): List<String>

    fun isCompilerPluginPath(classpath: String): Boolean

    fun matchingGradleBuildFiles(projectRoot: Path): List<String>

    fun matchingGradleBuildFiles(projectRoots: Iterable<Path>): List<String>

    fun matchingGradleBuildFileMatches(projectRoots: Iterable<Path>): List<GradleBuildFileMatch>

    fun isDirectGradlePluginReference(content: String): Boolean
}

class ConfiguredCompilerPluginDetectorFacade(
    private val descriptor: CompilerPluginDescriptor
) : CompilerPluginDetectorFacade {
    override fun scan(project: Project): CompilerPluginScan =
        CompilerPluginDetector.scan(project, descriptor)

    override fun matchingClasspaths(classpaths: Iterable<String>): List<String> =
        CompilerPluginDetector.matchingClasspaths(
            classpaths = classpaths,
            compilerPluginMarker = descriptor.compilerPluginMarker,
        )

    override fun isCompilerPluginPath(classpath: String): Boolean =
        CompilerPluginDetector.isCompilerPluginPath(
            classpath = classpath,
            compilerPluginMarker = descriptor.compilerPluginMarker,
        )

    override fun matchingGradleBuildFiles(projectRoot: Path): List<String> =
        CompilerPluginDetector.matchingGradleBuildFiles(
            projectRoot = projectRoot,
            gradlePluginId = descriptor.gradlePluginId,
        )

    override fun matchingGradleBuildFiles(projectRoots: Iterable<Path>): List<String> =
        CompilerPluginDetector.matchingGradleBuildFiles(
            projectRoots = projectRoots,
            gradlePluginId = descriptor.gradlePluginId,
        )

    override fun matchingGradleBuildFileMatches(
        projectRoots: Iterable<Path>
    ): List<GradleBuildFileMatch> =
        CompilerPluginDetector.matchingGradleBuildFileMatches(
            projectRoots = projectRoots,
            gradlePluginId = descriptor.gradlePluginId,
        )

    override fun isDirectGradlePluginReference(content: String): Boolean =
        CompilerPluginDetector.isDirectGradlePluginReference(
            content = content,
            gradlePluginId = descriptor.gradlePluginId,
        )
}

data class CompilerPluginIdeSupportDescriptor(
    val loggerCategory: Class<*>,
    val notificationGroupId: String,
    val supportDisplayName: String,
    val supportDisplayNameLowercase: String,
    val compilerPluginMarker: String,
    val compilerPluginDisplayName: String,
    val gradlePluginId: String,
    val externalPluginDisplayName: String,
    val analysisRestartReason: String,
    val enablementLogMessage: (Project) -> String,
    val waitingForGradleImportTitle: String =
        "$supportDisplayName IDE support is waiting for Gradle import",
    val enabledNowPrefix: String = "Enabled non-bundled K2 compiler plugins for this project session.",
    val alreadyEnabledPrefix: String =
        "Non-bundled K2 compiler plugins were already enabled for this project session.",
    val gradleImportDetectedName: String = compilerPluginDisplayName,
) {
    val detectorDescriptor: CompilerPluginDescriptor
        get() =
            CompilerPluginDescriptor(
                compilerPluginMarker = compilerPluginMarker,
                gradlePluginId = gradlePluginId,
            )

    val registryDescriptor: ExternalCompilerPluginRegistryDescriptor
        get() =
            ExternalCompilerPluginRegistryDescriptor(
                registryKey = EXTERNAL_K2_COMPILER_PLUGINS_REGISTRY_KEY,
                unavailableMessage =
                    "IntelliJ registry key $EXTERNAL_K2_COMPILER_PLUGINS_REGISTRY_KEY is unavailable; assuming external compiler plugins are not registry-blocked.",
                skippedEnablementMessage =
                    "IntelliJ registry key $EXTERNAL_K2_COMPILER_PLUGINS_REGISTRY_KEY is unavailable; skipping $externalPluginDisplayName external compiler-plugin enablement.",
            )

    val ideSupportMessages: IdeSupportMessages
        get() =
            IdeSupportMessages(
                noMatchesTitle = "$supportDisplayName IDE support",
                noMatchesContent =
                    "No imported Kotlin compiler arguments or Gradle build files reference $compilerPluginDisplayName or $gradlePluginId.",
                waitingForTrustTitle = "$supportDisplayName IDE support is waiting for trust",
                waitingForTrustContent =
                    "$compilerPluginDisplayName was detected, but IntelliJ will not load external compiler plugins until the project is trusted.",
                activeTitle = "$supportDisplayName IDE support is active",
                waitingForGradleImportTitle = waitingForGradleImportTitle,
                failedTitle = "$supportDisplayName IDE support could not be enabled",
            )
}

class ConfiguredCompilerPluginIdeSupport(
    val descriptor: CompilerPluginIdeSupportDescriptor
) {
    val detector: CompilerPluginDetectorFacade =
        ConfiguredCompilerPluginDetectorFacade(descriptor.detectorDescriptor)

    val coordinator =
        ConfiguredIdeSupportCoordinator(
            descriptor =
                IdeSupportCoordinatorDescriptor(
                    loggerCategory = descriptor.loggerCategory,
                    notificationGroupId = descriptor.notificationGroupId,
                    detector = detector::scan,
                    registry = descriptor.registryDescriptor,
                    messages = descriptor.ideSupportMessages,
                    enablementLogMessage = descriptor.enablementLogMessage,
                    buildEnabledMessage = ::buildEnabledMessage,
                    buildFailedEnablementMessage = ::buildFailedEnablementMessage,
                    analysisRestartReason = descriptor.analysisRestartReason,
                )
        )

    val registry =
        ConfiguredExternalCompilerPluginRegistry(
            descriptor = descriptor.registryDescriptor,
            loggerCategory = descriptor.loggerCategory,
        )

    fun buildEnabledMessage(
        scan: CompilerPluginScan,
        activationState: IdeSupportActivationState,
        gradleImportRequired: Boolean,
        gradleImportRequested: Boolean,
    ): String {
        val owners = buildIdeSupportOwners(scan).joinToString(", ")
        val prefix =
            when (activationState) {
                IdeSupportActivationState.ENABLED_NOW -> descriptor.enabledNowPrefix
                IdeSupportActivationState.ALREADY_ENABLED -> descriptor.alreadyEnabledPrefix
                IdeSupportActivationState.REGISTRY_UNAVAILABLE ->
                    "IntelliJ's external compiler-plugin registry key was unavailable, so ${descriptor.supportDisplayNameLowercase} IDE support is proceeding without registry changes."
                else -> error("buildEnabledMessage only supports successful activation states")
            }
        val detectedName =
            if (gradleImportRequired) {
                descriptor.gradleImportDetectedName
            } else {
                descriptor.compilerPluginDisplayName
            }
        val refreshMessage =
            when {
                gradleImportRequired && gradleImportRequested ->
                    " Requested a Gradle import because the compiler plugin classpath has not been imported yet."

                gradleImportRequired ->
                    " The Gradle plugin was detected, but the compiler plugin classpath is not imported yet. Reimport the Gradle project."

                else -> ""
            }
        return "$prefix Detected $detectedName in $owners.$refreshMessage"
    }

    fun buildFailedEnablementMessage(scan: CompilerPluginScan): String {
        val owners = buildIdeSupportOwners(scan).joinToString(", ")
        return "Detected ${descriptor.compilerPluginDisplayName} in $owners, but IntelliJ could not enable all non-bundled K2 compiler plugins for this project session."
    }
}
