plugins { java }

repositories { google(); mavenCentral() }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

dependencies {
    // Only compile against official profgen here. Runtime tools are supplied to
    // an isolated worker, leaving the root AGP plugin classloader unchanged.
    compileOnly("com.android.tools.build:gradle:8.7.3") { isTransitive = false }
    testImplementation("com.android.tools.build:gradle:8.7.3") { isTransitive = false }
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21") { isTransitive = false }
    testImplementation("junit:junit:4.13.2") { isTransitive = false }
    testRuntimeOnly("org.hamcrest:hamcrest-core:1.3") { isTransitive = false }
}
