# kotlin-ij-plugin-common

`kotlin-ij-plugin-common` is a shared IntelliJ-side helper library for Wabbit Kotlin compiler-plugin support plugins.

It holds the common IDE integration logic used by repositories such as `kotlin-acyclic`, `kotlin-typeclasses`, and `kotlin-no-globals`, including build-file detection, Gradle-import coordination, registry/session handling, and auto-rescan behavior.

## Who This Is For

This module is for maintainers building IntelliJ helper plugins for Wabbit compiler-plugin families.

It is not intended as a normal end-user dependency. End users should install or depend on the specific IDE support plugin for the compiler-plugin family they are using.

## Coordinates

- library artifact: `one.wabbit:kotlin-ij-plugin-common:0.0.1`

## Status

This library is pre-1.0 and intended for maintainers of Wabbit IntelliJ support plugins.

It centralizes shared IDE-support logic that is still evolving, so compatibility helpers and state-machine details may change between early releases.

## What It Provides

The shared library includes reusable support for:

- compiler-plugin and Gradle-build detection
- project-level IDE support state machines
- external compiler-plugin registry/session enablement
- Gradle-import request coordination
- auto-rescan hooks for trust changes, root changes, build-file edits, and import completion

## Installation

This library is meant for IntelliJ plugin implementation projects:

```kotlin
dependencies {
    implementation("one.wabbit:kotlin-ij-plugin-common:0.0.1")
}
```

The generated build already brings in the IntelliJ Platform Gradle plugin and the IntelliJ/Kotlin IDE surfaces needed by the shared code.

## Quick Start

Use the shared descriptors and coordinator wrappers to define plugin-specific IDE support without copying the same detector, notification, import, and registry plumbing into each IntelliJ plugin:

```kotlin
import one.wabbit.ijplugin.common.CompilerPluginIdeSupportDescriptor
import one.wabbit.ijplugin.common.ConfiguredCompilerPluginIdeSupport

private val support =
    ConfiguredCompilerPluginIdeSupport(
        CompilerPluginIdeSupportDescriptor(
            loggerCategory = MyIdeSupportActivity::class.java,
            notificationGroupId = "My Compiler Plugin",
            supportDisplayName = "My Compiler Plugin",
            supportDisplayNameLowercase = "my compiler plugin",
            compilerPluginMarker = "my-compiler-plugin",
            compilerPluginDisplayName = "my compiler plugin",
            gradlePluginId = "one.wabbit.my-plugin",
            externalPluginDisplayName = "non-bundled K2 compiler plugins",
            analysisRestartReason = "compiler-plugin activation",
            enablementLogMessage = { project ->
                "Enabling external compiler plugins for ${project.name}"
            },
        )
    )

class MyIdeSupportActivity :
    one.wabbit.ijplugin.common.ConfiguredIdeSupportActivity(support.coordinator)
```

That leaves the plugin-specific module responsible only for its descriptor values and any custom messaging, while the shared library owns the common scanning and activation behavior.

## Documentation

- [API reference](docs/api-reference.md)
- API docs are currently generated locally with `./gradlew dokkaGenerate`, then published from `build/dokka/html/index.html`.
- Main source entry points:
  - [`src/main/kotlin/one/wabbit/ijplugin/common/ConfiguredCompilerPluginSupport.kt`](src/main/kotlin/one/wabbit/ijplugin/common/ConfiguredCompilerPluginSupport.kt)
  - [`src/main/kotlin/one/wabbit/ijplugin/common/ConfiguredIdeSupport.kt`](src/main/kotlin/one/wabbit/ijplugin/common/ConfiguredIdeSupport.kt)

## Source Compatibility

- JVM target: 17
- IntelliJ Platform target in the generated build: `2025.3`
- Bundled Kotlin plugin dependency in the generated build: `org.jetbrains.kotlin`

## Release Notes

- [`CHANGELOG.md`](CHANGELOG.md)

## Related Repositories

- `kotlin-acyclic`
- `kotlin-typeclasses`
- `kotlin-no-globals`
