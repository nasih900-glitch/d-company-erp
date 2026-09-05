pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
