// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.ijplugin.common

import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class GradleBuildFileDetectionTest {
    @Test
    fun `gradleScanRoots drops descendant module roots already covered by ancestor settings`() {
        withManagedTestTempDirectory("ij-plugin-common-scan-roots-covered-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include(":app")
                    """
                        .trimIndent()
                )
            val appRoot = projectRoot.resolve("app").createDirectories()
            appRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val roots = CompilerPluginDetector.gradleScanRoots(listOf(projectRoot, appRoot))

            assertEquals(listOf(projectRoot), roots)
        }
    }

    @Test
    fun `gradleScanRoots keeps descendant standalone builds not covered by ancestor settings`() {
        withManagedTestTempDirectory("ij-plugin-common-scan-roots-standalone-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText("rootProject.name = \"demo\"")
            val nestedBuildRoot = projectRoot.resolve("nested").createDirectories()
            nestedBuildRoot
                .resolve("settings.gradle.kts")
                .writeText("rootProject.name = \"nested\"")
            nestedBuildRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val roots =
                CompilerPluginDetector.gradleScanRoots(listOf(projectRoot, nestedBuildRoot))

            assertEquals(listOf(projectRoot, nestedBuildRoot), roots)
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows projectDir remaps from settings`() {
        withManagedTestTempDirectory("ij-plugin-common-project-dir-remap-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include(":app")
                    project(":app").projectDir = file("modules/application")
                    """
                        .trimIndent()
                )
            projectRoot.resolve("modules/application").createDirectories()
            projectRoot
                .resolve("modules/application/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("modules/application/build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows buildFileName overrides from settings`() {
        withManagedTestTempDirectory("ij-plugin-common-build-file-name-override-test") {
            projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include(":project-a")
                    project(":project-a").buildFileName = "project-a.gradle"
                    """
                        .trimIndent()
                )
            projectRoot.resolve("project-a").createDirectories()
            projectRoot
                .resolve("project-a/project-a.gradle")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("project-a/project-a.gradle"), matches)
        }
    }

    @Test
    fun `watchedGradleRoots include project directories with custom buildFileName overrides`() {
        withManagedTestTempDirectory("ij-plugin-common-build-file-name-watch-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include(":project-a")
                    project(":project-a").buildFileName = "project-a.gradle.kts"
                    """
                        .trimIndent()
                )
            val projectADir = projectRoot.resolve("project-a").createDirectories()
            projectRoot
                .resolve("project-a/project-a.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val watchedRoots = CompilerPluginDetector.watchedGradleRoots(listOf(projectRoot))

            assertEquals(true, projectADir.toAbsolutePath().normalize() in watchedRoots)
        }
    }

    @Test
    fun `matchingGradleBuildFiles finds included subproject build scripts`() {
        withManagedTestTempDirectory("ij-plugin-common-subproject-build-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include(":app")
                    """
                        .trimIndent()
                )
            projectRoot.resolve("app").createDirectories()
            projectRoot
                .resolve("app/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("app/build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles expands implicit parent projects for nested includes`() {
        withManagedTestTempDirectory("ij-plugin-common-nested-include-parent-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include("a:b")
                    """
                        .trimIndent()
                )
            projectRoot.resolve("a").createDirectories()
            projectRoot
                .resolve("a/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("a/build.gradle.kts"), matches)
        }
    }

    @Test
    fun `watchedGradleRoots include implicit parent projects for nested includes`() {
        withManagedTestTempDirectory("ij-plugin-common-nested-include-watch-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include("a:b")
                    """
                        .trimIndent()
                )
            projectRoot.resolve("a").createDirectories()
            projectRoot
                .resolve("a/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val watchedRoots = CompilerPluginDetector.watchedGradleRoots(listOf(projectRoot))

            assertEquals(
                true,
                projectRoot.resolve("a").toAbsolutePath().normalize() in watchedRoots,
            )
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows includeFlat sibling projects`() {
        withManagedTestTempDirectory("ij-plugin-common-include-flat-test") { workspaceRoot ->
            val projectRoot = workspaceRoot.resolve("main")
            projectRoot.createDirectories()
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    includeFlat("shared-app")
                    """
                        .trimIndent()
                )
            workspaceRoot.resolve("shared-app").createDirectories()
            workspaceRoot
                .resolve("shared-app/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("../shared-app/build.gradle.kts"), matches)
        }
    }

    @Test
    fun `displayGradleBuildFilePath falls back to absolute path when relativize throws`() {
        val normalizedFile = Path.of("/tmp/external/build.gradle.kts").toAbsolutePath().normalize()
        val throwingRoot =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Path::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "relativize" -> throw IllegalArgumentException("cross-root path")
                    "toString" -> "X:/workspace"
                    "equals" -> false
                    "hashCode" -> System.identityHashCode(method)
                    else -> throw UnsupportedOperationException("Unexpected proxy call to ${method.name}(${args.orEmpty().toList()})")
                }
            } as Path

        val displayPath =
            CompilerPluginDetector.displayGradleBuildFilePath(
                normalizedFile = normalizedFile,
                displayRoot = null,
                scanTargetRoot = throwingRoot,
            )

        assertEquals(normalizedFile.toString().replace('\\', '/'), displayPath)
    }

    @Test
    fun `matchingGradleBuildFiles uses root version catalogs for included subprojects`() {
        withManagedTestTempDirectory("ij-plugin-common-root-alias-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    include(":app")
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("app").createDirectories()
            projectRoot
                .resolve("app/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(libs.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("app/build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles ignores apply false on version catalog aliases`() {
        withManagedTestTempDirectory("ij-plugin-common-alias-apply-false-test") { projectRoot ->
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(libs.plugins.demo).apply(false)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles ignores version apply false on version catalog aliases`() {
        withManagedTestTempDirectory("ij-plugin-common-alias-version-apply-false-test") {
            projectRoot ->
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(libs.plugins.demo) version "1.2.3" apply false
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows settings-defined version catalogs`() {
        withManagedTestTempDirectory("ij-plugin-common-settings-catalog-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    dependencyResolutionManagement {
                        versionCatalogs {
                            create("company") {
                                from(files("catalogs/company.versions.toml"))
                            }
                        }
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("catalogs").createDirectories()
            projectRoot
                .resolve("catalogs/company.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(company.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows Groovy shorthand settings-defined version catalogs`() {
        withManagedTestTempDirectory("ij-plugin-common-groovy-settings-catalog-test") {
            projectRoot ->
            projectRoot
                .resolve("settings.gradle")
                .writeText(
                    """
                    dependencyResolutionManagement {
                        versionCatalogs {
                            testLibs {
                                from(files('gradle/test-libs.versions.toml'))
                            }
                        }
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/test-libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(testLibs.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows settings-defined version catalogs through string vals`() {
        withManagedTestTempDirectory("ij-plugin-common-settings-catalog-val-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    val catalogPath = "catalogs/company.versions.toml"

                    dependencyResolutionManagement {
                        versionCatalogs {
                            create("company") {
                                from(files(catalogPath))
                            }
                        }
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("catalogs").createDirectories()
            projectRoot
                .resolve("catalogs/company.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(company.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows programmatic settings-defined plugin aliases`() {
        withManagedTestTempDirectory("ij-plugin-common-settings-programmatic-catalog-test") {
            projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    dependencyResolutionManagement {
                        versionCatalogs {
                            create("company") {
                                plugin("demo", "one.wabbit.demo").version("0.0.1")
                            }
                        }
                    }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(company.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles follows programmatic plugin aliases through string vals`() {
        withManagedTestTempDirectory("ij-plugin-common-settings-programmatic-catalog-val-test") {
            projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    val pluginAlias = "demo"
                    val pluginId = "one.wabbit.demo"

                    dependencyResolutionManagement {
                        versionCatalogs {
                            create("company") {
                                plugin(pluginAlias, pluginId).version("0.0.1")
                            }
                        }
                    }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(company.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles supports multiline version catalog plugin entries`() {
        withManagedTestTempDirectory("ij-plugin-common-multiline-catalog-plugin-test") {
            projectRoot ->
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = {
                      id = "one.wabbit.demo",
                      version = "0.0.1"
                    }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(libs.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles honors renamed default libraries extension`() {
        withManagedTestTempDirectory("ij-plugin-common-renamed-default-catalog-test") {
            projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    dependencyResolutionManagement {
                        defaultLibrariesExtensionName = "projectLibs"
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(projectLibs.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles does not assume libs alias after default catalog rename`() {
        withManagedTestTempDirectory("ij-plugin-common-renamed-default-catalog-miss-test") {
            projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    dependencyResolutionManagement {
                        defaultLibrariesExtensionName = "projectLibs"
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(libs.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles scans included build convention plugins`() {
        withManagedTestTempDirectory("ij-plugin-common-included-build-test") { projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    rootProject.name = "demo"
                    includeBuild("build-logic")
                    """
                        .trimIndent()
                )
            projectRoot.resolve("build-logic/src/main/kotlin").createDirectories()
            projectRoot
                .resolve("build-logic/build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        `kotlin-dsl`
                    }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build-logic/src/main/kotlin/demo-conventions.gradle.kts")
                .writeText(
                    """
                    plugins {
                        id("one.wabbit.demo")
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(
                listOf("build-logic/src/main/kotlin/demo-conventions.gradle.kts"),
                matches,
            )
        }
    }

    @Test
    fun `matchingGradleBuildFiles keeps version catalog identity when aliases collide`() {
        withManagedTestTempDirectory("ij-plugin-common-catalog-identity-test") { projectRoot ->
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/libs.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("gradle/company.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "com.example.other", version = "1.0.0" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(company.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles ignores undeclared non-default version catalogs in gradle directory`() {
        withManagedTestTempDirectory("ij-plugin-common-non-default-catalog-test") { projectRoot ->
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/company.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(company.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(emptyList(), matches)
        }
    }

    @Test
    fun `matchingGradleBuildFiles supports settings-declared non-default version catalog names`() {
        withManagedTestTempDirectory("ij-plugin-common-declared-non-default-catalog-test") {
            projectRoot ->
            projectRoot
                .resolve("settings.gradle.kts")
                .writeText(
                    """
                    dependencyResolutionManagement {
                        versionCatalogs {
                            create("company") {
                                from(files("gradle/company.versions.toml"))
                            }
                        }
                    }
                    """
                        .trimIndent()
                )
            projectRoot.resolve("gradle").createDirectories()
            projectRoot
                .resolve("gradle/company.versions.toml")
                .writeText(
                    """
                    [plugins]
                    demo = { id = "one.wabbit.demo", version = "0.0.1" }
                    """
                        .trimIndent()
                )
            projectRoot
                .resolve("build.gradle.kts")
                .writeText(
                    """
                    plugins {
                        alias(company.plugins.demo)
                    }
                    """
                        .trimIndent()
                )

            val matches =
                CompilerPluginDetector.matchingGradleBuildFiles(
                    projectRoot = projectRoot,
                    gradlePluginId = "one.wabbit.demo",
                )

            assertEquals(listOf("build.gradle.kts"), matches)
        }
    }

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
