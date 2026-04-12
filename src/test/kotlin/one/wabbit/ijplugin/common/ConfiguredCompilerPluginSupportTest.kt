// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.ijplugin.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfiguredCompilerPluginSupportTest {
    @Test
    fun `default waiting title says waiting for Gradle import`() {
        val descriptor =
            CompilerPluginIdeSupportDescriptor(
                loggerCategory = ConfiguredCompilerPluginSupportTest::class.java,
                notificationGroupId = "DemoSupport",
                supportDisplayName = "Demo",
                supportDisplayNameLowercase = "demo",
                compilerPluginMarker = "demo-plugin",
                compilerPluginDisplayName = "demo-plugin",
                gradlePluginId = "one.wabbit.demo",
                externalPluginDisplayName = "demo",
                analysisRestartReason = "Demo activation",
                enablementLogMessage = { _ -> "enable demo" },
            )

        assertEquals(
            "Demo IDE support is waiting for Gradle import",
            descriptor.ideSupportMessages.waitingForGradleImportTitle,
        )
    }

    @Test
    fun `custom waiting title is preserved`() {
        val descriptor =
            testDescriptor(waitingForGradleImportTitle = "Custom import title")

        assertEquals(
            "Custom import title",
            descriptor.ideSupportMessages.waitingForGradleImportTitle,
        )
    }

    private fun testDescriptor(
        waitingForGradleImportTitle: String? = null
    ): CompilerPluginIdeSupportDescriptor =
        CompilerPluginIdeSupportDescriptor(
            loggerCategory = ConfiguredCompilerPluginSupportTest::class.java,
            notificationGroupId = "DemoSupport",
            supportDisplayName = "Demo",
            supportDisplayNameLowercase = "demo",
            compilerPluginMarker = "demo-plugin",
            compilerPluginDisplayName = "demo-plugin",
            gradlePluginId = "one.wabbit.demo",
            externalPluginDisplayName = "demo",
            analysisRestartReason = "Demo activation",
            enablementLogMessage = { _ -> "enable demo" },
            waitingForGradleImportTitle = requireNotNull(waitingForGradleImportTitle),
        )
}
