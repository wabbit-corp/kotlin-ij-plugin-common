// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.ijplugin.common

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeSupportAutoRescanTest {
    @Test
    fun `initial rescan is scheduled asynchronously`() {
        val project = fakeProject("demo")
        val scheduled = mutableListOf<() -> Unit>()
        var rescans = 0

        IdeSupportAutoRescan.scheduleInitialRescan(
            project = project,
            requestRescan = { rescans += 1 },
            scheduleCallback = { callback ->
                {
                    scheduled += callback
                }
            },
        )

        assertEquals(0, rescans)
        assertEquals(1, scheduled.size)

        scheduled.single().invoke()

        assertEquals(1, rescans)
    }

    @Test
    fun `install coalesces repeated ordinary rescans`() {
        val project = fakeProject("demo")
        var importFinished: (() -> Unit)? = null
        var rootsChanged: (() -> Unit)? = null
        var projectTrusted: ((Project) -> Unit)? = null
        var buildFilesChanged: (() -> Unit)? = null
        val scheduled = mutableListOf<() -> Unit>()
        var rescans = 0

        IdeSupportAutoRescan.install(
            project = project,
            requestRescan = { rescans += 1 },
            requestGradleImportFinishedRescan = {},
            subscribeImportFinished = { callback -> importFinished = callback },
            subscribeRootsChanged = { callback -> rootsChanged = callback },
            subscribeProjectTrusted = { callback -> projectTrusted = callback },
            subscribeBuildFilesChanged = { callback -> buildFilesChanged = callback },
            debounceCallback = { callback ->
                var pending = false
                {
                    if (!pending) {
                        pending = true
                        scheduled += {
                            pending = false
                            callback()
                        }
                    }
                }
            },
        )

        requireNotNull(rootsChanged).invoke()
        requireNotNull(rootsChanged).invoke()
        requireNotNull(projectTrusted).invoke(project)
        requireNotNull(buildFilesChanged).invoke()
        requireNotNull(importFinished).invoke()

        assertEquals(0, rescans)
        assertEquals(2, scheduled.size)

        scheduled[0].invoke()
        assertEquals(1, rescans)
        scheduled[1].invoke()
        assertEquals(1, rescans)
    }

    @Test
    fun `build file changes under watched roots trigger rescans`() {
        val shouldRescan =
            IdeSupportAutoRescan.shouldRescanForBuildFileChanges(
                watchedRoots = listOf(Path.of("/repo")),
                changedPaths =
                    listOf(
                        "/repo/app/build.gradle.kts",
                        "/repo/gradle/libs.versions.toml",
                        "/repo/settings.gradle.kts",
                    ),
            )

        assertEquals(true, shouldRescan)
    }

    @Test
    fun `unrelated file changes do not trigger rescans`() {
        val shouldRescan =
            IdeSupportAutoRescan.shouldRescanForBuildFileChanges(
                watchedRoots = listOf(Path.of("/repo")),
                changedPaths =
                    listOf(
                        "/repo/src/main/kotlin/App.kt",
                        "/repo/docs/readme.md",
                        "/other/build.gradle.kts",
                    ),
            )

        assertEquals(false, shouldRescan)
    }

    @Test
    fun `unrelated file changes do not compute watched roots`() {
        var watchedRootsReads = 0

        val shouldRescan =
            IdeSupportAutoRescan.shouldRescanForBuildFileChanges(
                changedPaths =
                    listOf(
                        "/repo/src/main/kotlin/App.kt",
                        "/repo/docs/readme.md",
                        "/repo/assets/logo.png",
                    ),
                readWatchedRoots = {
                    watchedRootsReads += 1
                    listOf(Path.of("/repo"))
                },
            )

        assertEquals(false, shouldRescan)
        assertEquals(0, watchedRootsReads)
    }

    @Test
    fun `build file candidates compute watched roots`() {
        var watchedRootsReads = 0

        val shouldRescan =
            IdeSupportAutoRescan.shouldRescanForBuildFileChanges(
                changedPaths = listOf("/repo/build.gradle.kts"),
                readWatchedRoots = {
                    watchedRootsReads += 1
                    listOf(Path.of("/repo"))
                },
            )

        assertEquals(true, shouldRescan)
        assertEquals(1, watchedRootsReads)
    }

    @Test
    fun `convention plugin and external included build changes trigger rescans`() {
        withManagedTestTempDirectory("ij-plugin-common-auto-rescan-test") { workspaceRoot ->
            val projectRoot = workspaceRoot.resolve("app").createDirectories()
            val includedBuildRoot = workspaceRoot.resolve("build-logic").createDirectories()
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    includeBuild("../build-logic")
                    """
                        .trimIndent()
                )
            includedBuildRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        `kotlin-dsl`
                    }
                    """
                        .trimIndent()
                )

            val watchedRoots = CompilerPluginDetector.watchedGradleRoots(listOf(projectRoot))
            val shouldRescan =
                IdeSupportAutoRescan.shouldRescanForBuildFileChanges(
                    watchedRoots = watchedRoots,
                    changedPaths =
                        listOf(
                            projectRoot.resolve("buildSrc/src/main/kotlin/demo.gradle.kts").toString(),
                            projectRoot.resolve("build-logic/src/main/kotlin/demo.gradle.kts").toString(),
                            includedBuildRoot.resolve("src/main/kotlin/demo.gradle.kts").toString(),
                        ),
                )

            assertEquals(
                setOf(projectRoot.toAbsolutePath().normalize(), includedBuildRoot.toAbsolutePath().normalize()),
                watchedRoots.toSet(),
            )
            assertTrue(shouldRescan)
        }
    }

    @Test
    fun `out of root includeFlat build changes trigger rescans`() {
        withManagedTestTempDirectory("ij-plugin-common-auto-rescan-include-flat-test") {
            workspaceRoot ->
            val projectRoot = workspaceRoot.resolve("app").createDirectories()
            val siblingProjectRoot = workspaceRoot.resolve("build-logic").createDirectories()
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    includeFlat("build-logic")
                    """
                        .trimIndent()
                )
            siblingProjectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val watchedRoots = CompilerPluginDetector.watchedGradleRoots(listOf(projectRoot))
            val shouldRescan =
                IdeSupportAutoRescan.shouldRescanForBuildFileChanges(
                    watchedRoots = watchedRoots,
                    changedPaths = listOf(siblingProjectRoot.resolve("build.gradle.kts").toString()),
                )

            assertTrue(watchedRoots.contains(siblingProjectRoot.toAbsolutePath().normalize()))
            assertTrue(shouldRescan)
        }
    }

    private fun fakeProject(name: String): Project =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Project::class.java)) { _, method, _
            ->
            when (method.name) {
                "getName" -> name
                "toString" -> "fakeProject($name)"
                else -> unsupported(method.name)
            }
        } as Project

    private fun unsupported(methodName: String): Nothing =
        throw UnsupportedOperationException("Unexpected proxy call to $methodName")

    private inline fun <T> withManagedTestTempDirectory(
        prefix: String,
        block: (Path) -> T,
    ): T {
        val projectRoot = Files.createTempDirectory(prefix)
        try {
            return block(projectRoot)
        } finally {
            check(projectRoot.toFile().deleteRecursively()) {
                "Failed to delete temporary test directory $projectRoot"
            }
        }
    }
}
