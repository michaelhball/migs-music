plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

// Apply ktlint to every Kotlin subproject. Fast official-style formatter; blocks PRs that
// drift from standard Kotlin style without us having to think about trailing commas, import
// order, etc. Two rules are disabled as deliberate exceptions:
//   - `standard:function-naming` — Compose @Composable functions are PascalCase by convention,
//      which the rule (correctly for non-Compose code) flags as a violation.
//   - `standard:property-naming` — we use `_uiState` backing-property convention from the
//      Kotlin coroutines docs, plus the string constants in `UiTestTags` are camelCase by
//      design (they appear in Compose semantics tags, not as Kotlin compile-time constants).
subprojects {
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
    }
}
