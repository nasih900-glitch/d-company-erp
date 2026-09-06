plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 moved the Compose compiler into a first-party plugin; without
    // it, Compose builds fail with a compiler-version mismatch.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // KSP version is pinned to the Kotlin version; a mismatch fails the build.
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

// Release builds are independently reproduced before signing. Lock every
// resolvable project configuration so that both builders select the same
// dependency graph, while Gradle's verification metadata authenticates the
// bytes behind that graph.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
    }
}
