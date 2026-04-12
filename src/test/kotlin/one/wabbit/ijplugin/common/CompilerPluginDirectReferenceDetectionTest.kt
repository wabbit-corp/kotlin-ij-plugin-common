// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.ijplugin.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerPluginDirectReferenceDetectionTest {
    @Test
    fun `detects Groovy apply parenthesized plugin map syntax`() {
        assertTrue(
            CompilerPluginDetector.isDirectGradlePluginReference(
                content =
                    """
                    apply(plugin: "one.wabbit.demo")
                    """
                        .trimIndent(),
                gradlePluginId = "one.wabbit.demo",
            )
        )
    }

    @Test
    fun `does not detect chained apply false on plugin id`() {
        assertFalse(
            CompilerPluginDetector.isDirectGradlePluginReference(
                content =
                    """
                    plugins {
                        id("one.wabbit.demo").apply(false)
                    }
                    """
                        .trimIndent(),
                gradlePluginId = "one.wabbit.demo",
            )
        )
    }

    @Test
    fun `does not detect line wrapped apply false on plugin id`() {
        assertFalse(
            CompilerPluginDetector.isDirectGradlePluginReference(
                content =
                    """
                    plugins {
                        id("one.wabbit.demo")
                            apply false
                    }
                    """
                        .trimIndent(),
                gradlePluginId = "one.wabbit.demo",
            )
        )
    }

    @Test
    fun `does not detect version apply false on plugin id`() {
        assertFalse(
            CompilerPluginDetector.isDirectGradlePluginReference(
                content =
                    """
                    plugins {
                        id("one.wabbit.demo") version "1.2.3" apply false
                    }
                    """
                        .trimIndent(),
                gradlePluginId = "one.wabbit.demo",
            )
        )
    }

    @Test
    fun `does not detect chained version apply false on plugin id`() {
        assertFalse(
            CompilerPluginDetector.isDirectGradlePluginReference(
                content =
                    """
                    plugins {
                        id("one.wabbit.demo").version("1.2.3").apply(false)
                    }
                    """
                        .trimIndent(),
                gradlePluginId = "one.wabbit.demo",
            )
        )
    }
}
