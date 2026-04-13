# API Reference

`kotlin-ij-plugin-common` exposes shared IntelliJ support building blocks for Wabbit compiler-plugin families.

The main public surfaces live in:

- [`ConfiguredCompilerPluginSupport.kt`](../src/main/kotlin/one/wabbit/ijplugin/common/ConfiguredCompilerPluginSupport.kt)
- [`ConfiguredIdeSupport.kt`](../src/main/kotlin/one/wabbit/ijplugin/common/ConfiguredIdeSupport.kt)
- [`ConfiguredCompilerPluginWrappers.kt`](../src/main/kotlin/one/wabbit/ijplugin/common/ConfiguredCompilerPluginWrappers.kt)
- [`CompilerPluginDetection.kt`](../src/main/kotlin/one/wabbit/ijplugin/common/CompilerPluginDetection.kt)
- [`IdeSupport.kt`](../src/main/kotlin/one/wabbit/ijplugin/common/IdeSupport.kt)
- [`IdeSupportAutoRescan.kt`](../src/main/kotlin/one/wabbit/ijplugin/common/IdeSupportAutoRescan.kt)

For rendered API docs, run:

```bash
./gradlew dokkaGenerate
```

Then open:

```text
build/dokka/html/index.html
```
