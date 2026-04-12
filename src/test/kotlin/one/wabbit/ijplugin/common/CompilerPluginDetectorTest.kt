// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.ijplugin.common

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerPluginDetectorTest {
    @Test
    fun `scan collects project model state under a read action`() {
        val project = fakeProject("demo")
        var wrappedInReadAction = false
        var sawReadAccess = false

        val scan =
            CompilerPluginDetector.scan(
                project = project,
                descriptor =
                    CompilerPluginDescriptor(
                        compilerPluginMarker = "demo-plugin",
                        gradlePluginId = "one.wabbit.demo",
                    ),
                runReadAction = { action ->
                    wrappedInReadAction = true
                    sawReadAccess = true
                    action()
                },
                readProjectSnapshot = {
                    CompilerPluginProjectSnapshot(
                        projectName = "demo",
                        projectBasePath = null,
                        projectCompilerPluginClasspaths = emptyList(),
                        moduleSnapshots = emptyList(),
                    )
                },
            )

        assertTrue(wrappedInReadAction)
        assertTrue(sawReadAccess)
        assertFalse(scan.hasMatches)
    }

    @Test
    fun `file text cache is bounded and evicts least recently used entries`() {
        val tempDir = Files.createTempDirectory("ij-plugin-common-file-cache-test")
        try {
            GradleFileTextCache.clearForTesting()
            val maxEntries = GradleFileTextCache.maxEntriesForTesting()
            val files =
                (0..maxEntries).map { index ->
                    tempDir.resolve("build-$index.gradle.kts").also { file ->
                        file.writeText("""plugins { id("one.wabbit.demo.$index") }""")
                    }
                }

            files.take(maxEntries).forEach { file ->
                assertTrue(GradleFileTextCache.read(file) != null)
            }
            assertEquals(maxEntries, GradleFileTextCache.sizeForTesting())

            assertTrue(GradleFileTextCache.read(files.first()) != null)
            assertTrue(GradleFileTextCache.read(files.last()) != null)

            assertEquals(maxEntries, GradleFileTextCache.sizeForTesting())
            assertTrue(GradleFileTextCache.containsForTesting(files.first()))
            assertFalse(GradleFileTextCache.containsForTesting(files[1]))
            assertTrue(GradleFileTextCache.containsForTesting(files.last()))
        } finally {
            GradleFileTextCache.clearForTesting()
            check(tempDir.toFile().deleteRecursively()) {
                "Failed to delete temporary test directory $tempDir"
            }
        }
    }

    @Test
    fun `scan reuses cached parsed artifacts across plugin detector passes`() {
        val projectRoot = Files.createTempDirectory("ij-plugin-common-shared-scan-cache-test")
        try {
            val settingsFile = projectRoot.resolve("settings.gradle.kts")
            val buildFile = projectRoot.resolve("build.gradle.kts")
            val versionCatalogFile = projectRoot.resolve("gradle/libs.versions.toml")
            Files.createDirectories(versionCatalogFile.parent)
            settingsFile.writeText(
                """
                dependencyResolutionManagement {
                    versionCatalogs {
                        create("libs") {
                            from(files("gradle/libs.versions.toml"))
                        }
                    }
                }
                """.trimIndent()
            )
            buildFile.writeText(
                """
                plugins {
                    alias(libs.plugins.demo)
                    id("one.wabbit.other")
                }
                """.trimIndent()
            )
            versionCatalogFile.writeText(
                """
                [plugins]
                demo = { id = "one.wabbit.demo", version = "1.0.0" }
                other = { id = "one.wabbit.other", version = "1.0.0" }
                """.trimIndent()
            )

            GradleFileTextCache.clearForTesting()

            assertEquals(
                listOf("build.gradle.kts"),
                CompilerPluginDetector.matchingGradleBuildFiles(projectRoot, "one.wabbit.demo"),
            )
            assertEquals(
                listOf("build.gradle.kts"),
                CompilerPluginDetector.matchingGradleBuildFiles(projectRoot, "one.wabbit.other"),
            )
            assertEquals(
                listOf("build.gradle.kts"),
                CompilerPluginDetector.matchingGradleBuildFiles(projectRoot, "one.wabbit.demo"),
            )

            assertEquals(
                1,
                GradleFileTextCache.computationCountForTesting(
                    buildFile,
                    GradleFileTextCache.TOKENS_CACHE_KEY,
                ),
            )
            assertEquals(
                1,
                GradleFileTextCache.computationCountForTesting(
                    buildFile,
                    GradleFileTextCache.BUILD_SCRIPT_APPLICATIONS_CACHE_KEY,
                ),
            )
            assertEquals(
                1,
                GradleFileTextCache.computationCountForTesting(
                    settingsFile,
                    GradleFileTextCache.TOKENS_CACHE_KEY,
                ),
            )
            assertEquals(
                1,
                GradleFileTextCache.computationCountForTesting(
                    settingsFile,
                    GradleFileTextCache.PARSED_SETTINGS_CACHE_KEY_PREFIX +
                        projectRoot.toAbsolutePath().normalize(),
                ),
            )
            assertEquals(
                1,
                GradleFileTextCache.computationCountForTesting(
                    versionCatalogFile,
                    GradleFileTextCache.VERSION_CATALOG_PLUGIN_ALIAS_DEFINITIONS_CACHE_KEY,
                ),
            )
        } finally {
            GradleFileTextCache.clearForTesting()
            check(projectRoot.toFile().deleteRecursively()) {
                "Failed to delete temporary test directory $projectRoot"
            }
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
}
