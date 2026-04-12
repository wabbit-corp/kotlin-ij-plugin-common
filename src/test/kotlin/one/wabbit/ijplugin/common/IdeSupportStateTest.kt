// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.ijplugin.common

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeSupportStateTest {
    @Test
    fun `requests Gradle import only once while import is already in flight`() {
        val scan =
            CompilerPluginScan(
                projectLevelMatch = null,
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
            )
        val tracker = IdeSupportProjectStateTracker()
        val notifications = mutableListOf<Triple<NotificationType, String, String>>()
        var importRequests = 0
        val sessionKey = Any()

        val firstResult =
            enableIdeSupport(
                scan = scan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { type, title, content -> notifications += Triple(type, title, content) },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        val secondResult =
            enableIdeSupport(
                scan = scan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { type, title, content -> notifications += Triple(type, title, content) },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        assertTrue(firstResult.gradleImportRequested)
        assertFalse(secondResult.gradleImportRequested)
        assertEquals(1, importRequests)
        assertEquals(1, notifications.size)
        assertEquals(IdeSupportProjectState.IMPORT_IN_FLIGHT, tracker.currentState(sessionKey))
    }

    @Test
    fun `import finished rescan does not immediately request import again`() {
        val scan =
            CompilerPluginScan(
                projectLevelMatch = null,
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var importRequests = 0

        enableIdeSupport(
            scan = scan,
            projectTrusted = true,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
            },
            requestGradleImport = {
                importRequests += 1
                true
            },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        tracker.markImportFinished(sessionKey)

        val afterImportFinished =
            enableIdeSupport(
                scan = scan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        assertFalse(afterImportFinished.gradleImportRequested)
        assertEquals(1, importRequests)
        assertEquals(IdeSupportProjectState.WAITING_FOR_IMPORT, tracker.currentState(sessionKey))
    }

    @Test
    fun `build file signature change retries Gradle import after prior request`() {
        val initialScan =
            CompilerPluginScan(
                projectLevelMatch = null,
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
                gradleDetectionSignature = "gradle-scan-v1",
            )
        val updatedScan =
            initialScan.copy(gradleDetectionSignature = "gradle-scan-v2")
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var importRequests = 0

        enableIdeSupport(
            scan = initialScan,
            projectTrusted = true,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
            },
            requestGradleImport = {
                importRequests += 1
                true
            },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        tracker.markImportFinished(sessionKey)

        val result =
            enableIdeSupport(
                scan = updatedScan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        assertTrue(result.gradleImportRequested)
        assertEquals(2, importRequests)
        assertEquals(IdeSupportProjectState.IMPORT_IN_FLIGHT, tracker.currentState(sessionKey))
    }

    @Test
    fun `Gradle signature change requests import even when imported matches already exist`() {
        val initialScan =
            CompilerPluginScan(
                projectLevelMatch =
                    CompilerPluginMatch(ownerName = "demo", classpaths = listOf("/tmp/plugin.jar")),
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("module-a/build.gradle.kts"),
                gradleImportPaths = listOf("/repo/module-a"),
                gradleDetectionSignature = "gradle-scan-v1",
            )
        val updatedScan =
            initialScan.copy(
                gradleBuildFiles =
                    listOf(
                        "module-a/build.gradle.kts",
                        "module-b/build.gradle.kts",
                    ),
                gradleImportPaths = listOf("/repo/module-a", "/repo/module-b"),
                gradleDetectionSignature = "gradle-scan-v2",
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var importRequests = 0

        val initialResult =
            enableIdeSupport(
                scan = initialScan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        val updatedResult =
            enableIdeSupport(
                scan = updatedScan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        tracker.markImportFinished(sessionKey)

        val refreshedResult =
            enableIdeSupport(
                scan = updatedScan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        assertFalse(initialResult.gradleImportRequested)
        assertEquals(IdeSupportActivationState.ALREADY_ENABLED, initialResult.activationState)
        assertTrue(updatedResult.gradleImportRequested)
        assertEquals(1, importRequests)
        assertFalse(refreshedResult.gradleImportRequested)
        assertEquals(IdeSupportActivationState.ALREADY_ENABLED, refreshedResult.activationState)
        assertEquals(IdeSupportProjectState.ACTIVE, tracker.currentState(sessionKey))
    }

    @Test
    fun `failed Gradle import request does not suppress later automatic retry`() {
        val scan =
            CompilerPluginScan(
                projectLevelMatch = null,
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
                gradleDetectionSignature = "gradle-scan-v1",
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var importRequests = 0

        val firstResult =
            enableIdeSupport(
                scan = scan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    false
                },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        val secondResult =
            enableIdeSupport(
                scan = scan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        assertFalse(firstResult.gradleImportRequested)
        assertTrue(secondResult.gradleImportRequested)
        assertEquals(2, importRequests)
        assertEquals(IdeSupportProjectState.IMPORT_IN_FLIGHT, tracker.currentState(sessionKey))
    }

    @Test
    fun `waiting for trust notification only fires on transition`() {
        val scan =
            CompilerPluginScan(
                projectLevelMatch =
                    CompilerPluginMatch(ownerName = "demo", classpaths = listOf("/tmp/plugin.jar")),
                moduleMatches = emptyList(),
                gradleBuildFiles = emptyList(),
            )
        val tracker = IdeSupportProjectStateTracker()
        val notifications = mutableListOf<Triple<NotificationType, String, String>>()
        val sessionKey = Any()

        repeat(2) {
            enableIdeSupport(
                scan = scan,
                projectTrusted = false,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.BLOCKING,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE
                },
                notify = { type, title, content -> notifications += Triple(type, title, content) },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )
        }

        assertEquals(1, notifications.size)
        assertEquals(IdeSupportProjectState.WAITING_FOR_TRUST, tracker.currentState(sessionKey))
    }

    @Test
    fun `registry unavailability skips registration and still activates support`() {
        val scan =
            CompilerPluginScan(
                projectLevelMatch =
                    CompilerPluginMatch(ownerName = "demo", classpaths = listOf("/tmp/plugin.jar")),
                moduleMatches = emptyList(),
                gradleBuildFiles = emptyList(),
            )
        var registrationAttempts = 0
        val notifications = mutableListOf<Triple<NotificationType, String, String>>()

        val result =
            enableIdeSupport(
                scan = scan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.UNAVAILABLE,
                enableExternalPluginsForProjectSession = {
                    registrationAttempts += 1
                    ExternalCompilerPluginSessionRegistrationResult.FAILED
                },
                notify = { type, title, content -> notifications += Triple(type, title, content) },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
            )

        assertEquals(0, registrationAttempts)
        assertEquals(ExternalCompilerPluginRegistryState.UNAVAILABLE, result.registryState)
        assertEquals(IdeSupportActivationState.REGISTRY_UNAVAILABLE, result.activationState)
        assertFalse(result.registryAlreadyEnabledForExternalPlugins)
        assertFalse(result.registryUpdated)
        assertEquals(1, notifications.size)
        assertTrue(notifications.single().third.contains("REGISTRY_UNAVAILABLE"))
    }

    @Test
    fun `build file only scan does not restart analysis even when registry changes`() {
        val scan =
            CompilerPluginScan(
                projectLevelMatch = null,
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var analysisRestarts = 0

        val result =
            enableIdeSupport(
                scan = scan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.BLOCKING,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE
                },
                requestGradleImport = { true },
                restartAnalysis = { analysisRestarts += 1 },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        assertEquals(IdeSupportActivationState.WAITING_FOR_GRADLE_IMPORT, result.activationState)
        assertTrue(result.registryUpdated)
        assertTrue(result.gradleImportRequested)
        assertEquals(0, analysisRestarts)
    }

    @Test
    fun `analysis restarts when imported compiler plugin matches become available after import finishes`() {
        val buildFileOnlyScan =
            CompilerPluginScan(
                projectLevelMatch = null,
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
            )
        val importedScan =
            CompilerPluginScan(
                projectLevelMatch =
                    CompilerPluginMatch(ownerName = "demo", classpaths = listOf("/tmp/plugin.jar")),
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var analysisRestarts = 0

        enableIdeSupport(
            scan = buildFileOnlyScan,
            projectTrusted = true,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
            },
            requestGradleImport = { true },
            restartAnalysis = { analysisRestarts += 1 },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        tracker.markImportFinished(sessionKey)

        enableIdeSupport(
            scan = importedScan,
            projectTrusted = true,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
            },
            restartAnalysis = { analysisRestarts += 1 },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        enableIdeSupport(
            scan = importedScan,
            projectTrusted = true,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
            },
            restartAnalysis = { analysisRestarts += 1 },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        assertEquals(1, analysisRestarts)
        assertEquals(IdeSupportProjectState.ACTIVE, tracker.currentState(sessionKey))
    }

    @Test
    fun `import in flight does not treat stale imported classpaths as active`() {
        val buildFileOnlyScan =
            CompilerPluginScan(
                projectLevelMatch = null,
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
                gradleDetectionSignature = "gradle-scan-v1",
            )
        val staleImportedScan =
            CompilerPluginScan(
                projectLevelMatch =
                    CompilerPluginMatch(ownerName = "demo", classpaths = listOf("/tmp/plugin.jar")),
                moduleMatches = emptyList(),
                gradleBuildFiles = listOf("build.gradle.kts"),
                gradleImportPaths = listOf("/repo/app"),
                gradleDetectionSignature = "gradle-scan-v1",
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var importRequests = 0
        var analysisRestarts = 0

        val initialResult =
            enableIdeSupport(
                scan = buildFileOnlyScan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                restartAnalysis = { analysisRestarts += 1 },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        val inFlightResult =
            enableIdeSupport(
                scan = staleImportedScan,
                projectTrusted = true,
                userInitiated = false,
                registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
                enableExternalPluginsForProjectSession = {
                    ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
                },
                requestGradleImport = {
                    importRequests += 1
                    true
                },
                restartAnalysis = { analysisRestarts += 1 },
                notify = { _, _, _ -> },
                messages = testMessages(),
                buildEnabledMessage = ::buildTestEnabledMessage,
                buildFailedEnablementMessage = { "failed" },
                sessionKey = sessionKey,
                projectStateTracker = tracker,
            )

        assertTrue(initialResult.gradleImportRequested)
        assertEquals(IdeSupportActivationState.WAITING_FOR_GRADLE_IMPORT, initialResult.activationState)
        assertFalse(inFlightResult.gradleImportRequested)
        assertEquals(IdeSupportActivationState.WAITING_FOR_GRADLE_IMPORT, inFlightResult.activationState)
        assertEquals(1, importRequests)
        assertEquals(0, analysisRestarts)
        assertEquals(IdeSupportProjectState.IMPORT_IN_FLIGHT, tracker.currentState(sessionKey))
    }

    @Test
    fun `analysis restarts when trust makes imported compiler plugin usable`() {
        val importedScan =
            CompilerPluginScan(
                projectLevelMatch =
                    CompilerPluginMatch(ownerName = "demo", classpaths = listOf("/tmp/plugin.jar")),
                moduleMatches = emptyList(),
                gradleBuildFiles = emptyList(),
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var analysisRestarts = 0

        enableIdeSupport(
            scan = importedScan,
            projectTrusted = false,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
            },
            restartAnalysis = { analysisRestarts += 1 },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        enableIdeSupport(
            scan = importedScan,
            projectTrusted = true,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.ALREADY_ALLOWED,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE
            },
            restartAnalysis = { analysisRestarts += 1 },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        assertEquals(1, analysisRestarts)
        assertEquals(IdeSupportProjectState.ACTIVE, tracker.currentState(sessionKey))
    }

    @Test
    fun `analysis restarts when registry flip makes imported compiler plugin usable`() {
        val importedScan =
            CompilerPluginScan(
                projectLevelMatch =
                    CompilerPluginMatch(ownerName = "demo", classpaths = listOf("/tmp/plugin.jar")),
                moduleMatches = emptyList(),
                gradleBuildFiles = emptyList(),
            )
        val tracker = IdeSupportProjectStateTracker()
        val sessionKey = Any()
        var analysisRestarts = 0

        enableIdeSupport(
            scan = importedScan,
            projectTrusted = false,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.BLOCKING,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE
            },
            restartAnalysis = { analysisRestarts += 1 },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        enableIdeSupport(
            scan = importedScan,
            projectTrusted = true,
            userInitiated = false,
            registryState = ExternalCompilerPluginRegistryState.BLOCKING,
            enableExternalPluginsForProjectSession = {
                ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE
            },
            restartAnalysis = { analysisRestarts += 1 },
            notify = { _, _, _ -> },
            messages = testMessages(),
            buildEnabledMessage = ::buildTestEnabledMessage,
            buildFailedEnablementMessage = { "failed" },
            sessionKey = sessionKey,
            projectStateTracker = tracker,
        )

        assertEquals(1, analysisRestarts)
        assertEquals(IdeSupportProjectState.ACTIVE, tracker.currentState(sessionKey))
    }

    @Test
    fun `registry session stays active until the last dependent project closes`() {
        var allowsOnlyBundledPlugins = true
        val projectA = fakeDisposableProject("A")
        val projectB = fakeDisposableProject("B")

        val firstResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.test",
                project = projectA,
                read = { allowsOnlyBundledPlugins },
                update = { value -> allowsOnlyBundledPlugins = value },
            )
        val secondResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.test",
                project = projectB,
                read = { allowsOnlyBundledPlugins },
                update = { value -> allowsOnlyBundledPlugins = value },
            )

        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE,
            firstResult,
        )
        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE,
            secondResult,
        )
        assertFalse(allowsOnlyBundledPlugins)

        Disposer.dispose(projectA)
        assertFalse(allowsOnlyBundledPlugins)

        Disposer.dispose(projectB)
        assertTrue(allowsOnlyBundledPlugins)
    }

    @Test
    fun `failed enablement rolls back registry bookkeeping`() {
        var allowsOnlyBundledPlugins = true
        var failNextUpdate = true
        val failedProject = fakeDisposableProject("failed")
        val succeedingProject = fakeDisposableProject("succeeding")

        val failedResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.failed-enable",
                project = failedProject,
                read = { allowsOnlyBundledPlugins },
                update = { value ->
                    if (failNextUpdate) {
                        throw IllegalStateException("boom")
                    }
                    allowsOnlyBundledPlugins = value
                },
            )

        assertEquals(ExternalCompilerPluginSessionRegistrationResult.FAILED, failedResult)
        assertTrue(allowsOnlyBundledPlugins)

        failNextUpdate = false

        val succeedingResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.failed-enable",
                project = succeedingProject,
                read = { allowsOnlyBundledPlugins },
                update = { value -> allowsOnlyBundledPlugins = value },
            )

        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE,
            succeedingResult,
        )
        assertFalse(allowsOnlyBundledPlugins)

        Disposer.dispose(succeedingProject)

        assertTrue(allowsOnlyBundledPlugins)
    }

    @Test
    fun `first registration reads registry only once`() {
        var allowsOnlyBundledPlugins = true
        var readCalls = 0
        val project = fakeDisposableProject("read-once")

        val result =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.read-once",
                project = project,
                read = {
                    readCalls += 1
                    allowsOnlyBundledPlugins
                },
                update = { value -> allowsOnlyBundledPlugins = value },
            )

        assertEquals(ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE, result)
        assertEquals(1, readCalls)

        Disposer.dispose(project)
        assertTrue(allowsOnlyBundledPlugins)
    }

    @Test
    fun `failed disposer registration restores registry and rolls back bookkeeping`() {
        var allowsOnlyBundledPlugins = true
        val failedProject = fakeDisposableProject("failed-disposer")
        val succeedingProject = fakeDisposableProject("succeeding-disposer")

        val failedResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.failed-disposer",
                project = failedProject,
                read = { allowsOnlyBundledPlugins },
                update = { value -> allowsOnlyBundledPlugins = value },
                registerDisposer = { _, _ -> throw IllegalStateException("register failed") },
            )

        assertEquals(ExternalCompilerPluginSessionRegistrationResult.FAILED, failedResult)
        assertTrue(allowsOnlyBundledPlugins)

        val succeedingResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.failed-disposer",
                project = succeedingProject,
                read = { allowsOnlyBundledPlugins },
                update = { value -> allowsOnlyBundledPlugins = value },
            )

        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE,
            succeedingResult,
        )
        assertFalse(allowsOnlyBundledPlugins)

        Disposer.dispose(succeedingProject)

        assertTrue(allowsOnlyBundledPlugins)
    }

    @Test
    fun `failed restore keeps original value available for a later retry`() {
        var allowsOnlyBundledPlugins = true
        var failRestore = false
        val firstProject = fakeDisposableProject("first")
        val secondProject = fakeDisposableProject("second")

        val firstResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.failed-restore",
                project = firstProject,
                read = { allowsOnlyBundledPlugins },
                update = { value ->
                    if (value && failRestore) {
                        throw IllegalStateException("restore failed")
                    }
                    allowsOnlyBundledPlugins = value
                },
            )

        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE,
            firstResult,
        )
        assertFalse(allowsOnlyBundledPlugins)

        failRestore = true
        Disposer.dispose(firstProject)

        assertFalse(allowsOnlyBundledPlugins)

        failRestore = false

        val secondResult =
            ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                registryKey = "external.plugins.failed-restore",
                project = secondProject,
                read = { allowsOnlyBundledPlugins },
                update = { value ->
                    if (value && failRestore) {
                        throw IllegalStateException("restore failed")
                    }
                    allowsOnlyBundledPlugins = value
                },
            )

        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE,
            secondResult,
        )
        assertFalse(allowsOnlyBundledPlugins)

        Disposer.dispose(secondProject)

        assertTrue(allowsOnlyBundledPlugins)
    }

    @Test
    fun `concurrent registration waits for the first update before reporting already enabled`() {
        var allowsOnlyBundledPlugins = true
        val firstProject = fakeDisposableProject("concurrent-first")
        val secondProject = fakeDisposableProject("concurrent-second")
        val updateStarted = CountDownLatch(1)
        val allowFirstUpdateToFinish = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val firstResult = AtomicReference<ExternalCompilerPluginSessionRegistrationResult?>()
        val secondResult = AtomicReference<ExternalCompilerPluginSessionRegistrationResult?>()

        val firstThread =
            thread(start = true) {
                firstResult.set(
                    ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                        registryKey = "external.plugins.concurrent-enable",
                        project = firstProject,
                        read = { allowsOnlyBundledPlugins },
                        update = { value ->
                            updateStarted.countDown()
                            assertTrue(allowFirstUpdateToFinish.await(5, TimeUnit.SECONDS))
                            allowsOnlyBundledPlugins = value
                        },
                    )
                )
            }

        assertTrue(updateStarted.await(5, TimeUnit.SECONDS))

        val secondThread =
            thread(start = true) {
                secondResult.set(
                    ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                        registryKey = "external.plugins.concurrent-enable",
                        project = secondProject,
                        read = { allowsOnlyBundledPlugins },
                        update = { value -> allowsOnlyBundledPlugins = value },
                    )
                )
                secondCompleted.countDown()
            }

        assertFalse(secondCompleted.await(200, TimeUnit.MILLISECONDS))

        allowFirstUpdateToFinish.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE,
            firstResult.get(),
        )
        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.REGISTERED_WITHOUT_CHANGE,
            secondResult.get(),
        )
        assertFalse(allowsOnlyBundledPlugins)

        Disposer.dispose(firstProject)
        assertFalse(allowsOnlyBundledPlugins)
        Disposer.dispose(secondProject)
        assertTrue(allowsOnlyBundledPlugins)
    }

    @Test
    fun `concurrent registration retries after the first update fails`() {
        var allowsOnlyBundledPlugins = true
        val firstProject = fakeDisposableProject("concurrent-failed-first")
        val secondProject = fakeDisposableProject("concurrent-failed-second")
        val updateStarted = CountDownLatch(1)
        val allowFirstUpdateToFail = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val firstResult = AtomicReference<ExternalCompilerPluginSessionRegistrationResult?>()
        val secondResult = AtomicReference<ExternalCompilerPluginSessionRegistrationResult?>()

        val firstThread =
            thread(start = true) {
                firstResult.set(
                    ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                        registryKey = "external.plugins.concurrent-failed-enable",
                        project = firstProject,
                        read = { allowsOnlyBundledPlugins },
                        update = {
                            updateStarted.countDown()
                            assertTrue(allowFirstUpdateToFail.await(5, TimeUnit.SECONDS))
                            throw IllegalStateException("boom")
                        },
                    )
                )
            }

        assertTrue(updateStarted.await(5, TimeUnit.SECONDS))

        val secondThread =
            thread(start = true) {
                secondResult.set(
                    ExternalCompilerPluginRegistrySupport.enableExternalPluginsForProjectSession(
                        registryKey = "external.plugins.concurrent-failed-enable",
                        project = secondProject,
                        read = { allowsOnlyBundledPlugins },
                        update = { value -> allowsOnlyBundledPlugins = value },
                    )
                )
                secondCompleted.countDown()
            }

        assertFalse(secondCompleted.await(200, TimeUnit.MILLISECONDS))

        allowFirstUpdateToFail.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertEquals(ExternalCompilerPluginSessionRegistrationResult.FAILED, firstResult.get())
        assertEquals(
            ExternalCompilerPluginSessionRegistrationResult.CHANGED_VALUE,
            secondResult.get(),
        )
        assertFalse(allowsOnlyBundledPlugins)

        Disposer.dispose(secondProject)
        assertTrue(allowsOnlyBundledPlugins)
    }

    private fun testMessages(): IdeSupportMessages =
        IdeSupportMessages(
            noMatchesTitle = "Support",
            noMatchesContent = "no matches",
            waitingForTrustTitle = "Waiting for trust",
            waitingForTrustContent = "trust",
            activeTitle = "Active",
            waitingForGradleImportTitle = "Waiting for import",
            failedTitle = "Failed",
        )

    private fun buildTestEnabledMessage(
        scan: CompilerPluginScan,
        activationState: IdeSupportActivationState,
        gradleImportRequired: Boolean,
        gradleImportRequested: Boolean,
    ): String =
        "state=$activationState required=$gradleImportRequired import=$gradleImportRequested files=${scan.gradleBuildFiles.size}"

    private fun fakeDisposableProject(name: String): Project {
        var disposed = false
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Project::class.java)) {
            proxy,
            method,
            args,
            ->
            when (method.name) {
                "getName" -> name
                "dispose" -> {
                    disposed = true
                    Unit
                }
                "isDisposed" -> disposed
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "toString" -> "fakeDisposableProject($name)"
                else -> defaultValue(method.returnType)
            }
        } as Project
    }

    private fun defaultValue(returnType: Class<*>): Any? =
        when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Void.TYPE -> Unit
            else -> null
        }
}
