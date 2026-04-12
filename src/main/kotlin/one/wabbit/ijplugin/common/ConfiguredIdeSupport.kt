// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.ijplugin.common

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.registry.Registry

data class ExternalCompilerPluginRegistryDescriptor(
    val registryKey: String,
    val unavailableMessage: String,
    val skippedEnablementMessage: String,
)

class ConfiguredExternalCompilerPluginRegistry(
    private val descriptor: ExternalCompilerPluginRegistryDescriptor,
    loggerCategory: Class<*>,
) {
    private val logger = Logger.getInstance(loggerCategory)

    fun registryState(): ExternalCompilerPluginRegistryState =
        registryState(
            read = { Registry.`is`(descriptor.registryKey, false) },
            logFailure = { error -> logger.warn(descriptor.unavailableMessage, error) },
        )

    fun registryState(
        read: () -> Boolean,
        logFailure: (Throwable) -> Unit = {},
    ): ExternalCompilerPluginRegistryState =
        ExternalCompilerPluginRegistrySupport.registryState(read, logFailure)

    fun enableExternalPluginsForProjectSession(
        project: Project
    ): ExternalCompilerPluginSessionRegistrationResult =
        ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
            registryKey = descriptor.registryKey,
            project = project,
            read = { Registry.`is`(descriptor.registryKey, false) },
            update = { value -> Registry.get(descriptor.registryKey).setValue(value) },
            logFailure = { error -> logger.warn(descriptor.skippedEnablementMessage, error) },
        )

    fun enableExternalPluginsForProjectSession(
        update: () -> Unit,
        logFailure: (Throwable) -> Unit = {},
    ): ExternalCompilerPluginSessionRegistrationResult =
        runCatching {
                update()
                ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE
            }
            .getOrElse { error ->
                logFailure(error)
                ExternalCompilerPluginSessionRegistrationResult.FAILED
            }
}

interface IdeSupportGradleImportRequester {
    fun requestImport(project: Project, externalProjectPaths: List<String>): Boolean
}

class DefaultIdeSupportGradleImportRequester(
    private val projectSystemId: ProjectSystemId,
    loggerCategory: Class<*>,
) : IdeSupportGradleImportRequester {
    private val logger = Logger.getInstance(loggerCategory)

    override fun requestImport(project: Project, externalProjectPaths: List<String>): Boolean =
        requestExternalSystemImport(
            project = project,
            externalProjectPaths = externalProjectPaths,
            projectSystemId = projectSystemId,
        ) { externalProjectPath, throwable ->
            logger.warn(
                "Could not request Gradle import for ${project.name} at $externalProjectPath",
                throwable,
            )
        }
}

interface IdeSupportAnalysisRestarter {
    fun restart(project: Project)
}

class DefaultIdeSupportAnalysisRestarter(private val reason: String) : IdeSupportAnalysisRestarter {
    override fun restart(project: Project) {
        restartAnalysis(project, reason)
    }
}

data class IdeSupportCoordinatorDescriptor(
    val loggerCategory: Class<*>,
    val notificationGroupId: String,
    val detector: (Project) -> CompilerPluginScan,
    val registry: ExternalCompilerPluginRegistryDescriptor,
    val messages: IdeSupportMessages,
    val enablementLogMessage: (Project) -> String,
    val buildEnabledMessage:
        (CompilerPluginScan, IdeSupportActivationState, Boolean, Boolean) -> String,
    val buildFailedEnablementMessage: (CompilerPluginScan) -> String,
    val analysisRestartReason: String,
    val gradleProjectSystemId: ProjectSystemId = ProjectSystemId("GRADLE"),
)

interface ProjectIdeSupportCoordinator {
    fun onGradleImportFinished(project: Project): IdeSupportResult<CompilerPluginScan>

    fun enableIfNeeded(project: Project, userInitiated: Boolean): IdeSupportResult<CompilerPluginScan>
}

class ConfiguredIdeSupportCoordinator(private val descriptor: IdeSupportCoordinatorDescriptor) :
    ProjectIdeSupportCoordinator {
    private val logger = Logger.getInstance(descriptor.loggerCategory)
    private val registry = ConfiguredExternalCompilerPluginRegistry(descriptor.registry, descriptor.loggerCategory)
    private val projectStateTracker = IdeSupportProjectStateTracker()

    var gradleImportRequester: IdeSupportGradleImportRequester =
        DefaultIdeSupportGradleImportRequester(
            projectSystemId = descriptor.gradleProjectSystemId,
            loggerCategory = descriptor.loggerCategory,
        )

    var analysisRestarter: IdeSupportAnalysisRestarter =
        DefaultIdeSupportAnalysisRestarter(descriptor.analysisRestartReason)

    override fun onGradleImportFinished(project: Project): IdeSupportResult<CompilerPluginScan> {
        projectStateTracker.markImportFinished(project)
        return enableIfNeeded(project = project, userInitiated = false)
    }

    override fun enableIfNeeded(
        project: Project,
        userInitiated: Boolean,
    ): IdeSupportResult<CompilerPluginScan> {
        val scan = descriptor.detector(project)
        val trusted = TrustedProjects.isProjectTrusted(project)
        val registryState = registry.registryState()
        return enableIfNeeded(
            scan = scan,
            projectTrusted = trusted,
            userInitiated = userInitiated,
            registryState = registryState,
            enableExternalPluginsForProjectSession = {
                if (registryState == ExternalCompilerPluginRegistryState.BLOCKING) {
                    logger.info(descriptor.enablementLogMessage(project))
                }
                registry.enableExternalPluginsForProjectSession(project)
            },
            requestGradleImport = { externalProjectPaths ->
                gradleImportRequester.requestImport(
                    project = project,
                    externalProjectPaths =
                        externalProjectPaths.ifEmpty {
                            gradleImportPaths(scan, project.basePath)
                        },
                )
            },
            restartAnalysis = { analysisRestarter.restart(project) },
            notify = { type, title, content ->
                notify(project = project, type = type, title = title, content = content)
            },
            sessionKey = project,
            projectStateTracker = projectStateTracker,
        )
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
        enableIdeSupport(
            scan = scan,
            projectTrusted = projectTrusted,
            userInitiated = userInitiated,
            registryState = registryState,
            enableExternalPluginsForProjectSession = enableExternalPluginsForProjectSession,
            requestGradleImport = requestGradleImport,
            restartAnalysis = restartAnalysis,
            notify = notify,
            messages = descriptor.messages,
            buildEnabledMessage = descriptor.buildEnabledMessage,
            buildFailedEnablementMessage = descriptor.buildFailedEnablementMessage,
            sessionKey = sessionKey,
            projectStateTracker = projectStateTracker,
        )

    fun buildEnabledMessage(
        scan: CompilerPluginScan,
        activationState: IdeSupportActivationState,
        gradleImportRequired: Boolean,
        gradleImportRequested: Boolean,
    ): String =
        descriptor.buildEnabledMessage(
            scan,
            activationState,
            gradleImportRequired,
            gradleImportRequested,
        )

    fun buildFailedEnablementMessage(scan: CompilerPluginScan): String =
        descriptor.buildFailedEnablementMessage(scan)

    fun gradleImportPaths(scan: CompilerPluginScan, projectBasePath: String?): List<String> =
        one.wabbit.ijplugin.common.gradleImportPaths(scan, projectBasePath)

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(descriptor.notificationGroupId)
            .createNotification(title, content, type)
            .notify(project)
    }

    fun registry(): ConfiguredExternalCompilerPluginRegistry = registry
}

abstract class BaseIdeSupportActivity : ProjectActivity {
    protected abstract val coordinator: ProjectIdeSupportCoordinator

    override suspend fun execute(project: Project) {
        fun requestRescan() {
            coordinator.enableIfNeeded(project = project, userInitiated = false)
        }

        fun requestGradleImportFinishedRescan() {
            coordinator.onGradleImportFinished(project)
        }

        IdeSupportAutoRescan.install(
            project = project,
            requestRescan = ::requestRescan,
            requestGradleImportFinishedRescan = ::requestGradleImportFinishedRescan,
        )
        IdeSupportAutoRescan.scheduleInitialRescan(
            project = project,
            requestRescan = ::requestRescan,
        )
    }
}

open class ConfiguredIdeSupportActivity(
    final override val coordinator: ProjectIdeSupportCoordinator
) : BaseIdeSupportActivity()

abstract class BaseRefreshIdeSupportAction(title: String, description: String) :
    DumbAwareAction(title, description, null) {
    protected abstract val coordinator: ProjectIdeSupportCoordinator

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        coordinator.enableIfNeeded(project = project, userInitiated = true)
    }
}

open class ConfiguredRefreshIdeSupportAction(
    title: String,
    description: String,
    final override val coordinator: ProjectIdeSupportCoordinator,
) : BaseRefreshIdeSupportAction(title, description)
