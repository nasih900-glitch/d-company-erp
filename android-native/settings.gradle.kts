pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        exclusiveContent {
            forRepository { mavenCentral() }
            filter {
                includeModule("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin")
                includeModule("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin")
                includeModule(
                    "org.jetbrains.kotlin.plugin.serialization",
                    "org.jetbrains.kotlin.plugin.serialization.gradle.plugin",
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "D Company ERP"
include(":app")
// Separate process: the audit can force-stop/relaunch ERP without killing its
// own instrumentation. This application is test infrastructure, never shipped.
include(":audit-driver")
