// Explicit imports: inside a Kotlin DSL build script the Gradle `java`
// extension shadows the `java.*` package, so `java.util.Properties` fails to
// resolve without these.
import java.io.FileInputStream
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Reuse the SAME release keystore as the Capacitor build. Android identifies
// an app by (applicationId, signing key); signing this with a different key
// while it shares cloud.dcompany.erp would make it refuse to install over the
// existing app on the partners' tablets.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val productionApiBaseUrl = "https://dcompany.duckdns.org/api/v1/"
val debugApiBaseUrl = providers.gradleProperty("dcompany.debugApiBaseUrl")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: productionApiBaseUrl
val debugApiUri = runCatching { URI(debugApiBaseUrl) }.getOrNull()
val androidTestBuildType = providers.gradleProperty("dcompany.androidTestBuildType")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: "debug"
require(androidTestBuildType in setOf("debug", "physicalAudit")) {
    "dcompany.androidTestBuildType must be 'debug' or 'physicalAudit'."
}
require(debugApiBaseUrl.endsWith("/")) {
    "dcompany.debugApiBaseUrl must end with '/'."
}
require(
    debugApiUri?.scheme == "https" ||
        (debugApiUri?.scheme == "http" && debugApiUri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")),
) {
    "dcompany.debugApiBaseUrl must use HTTPS, or HTTP on a local emulator-test host."
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "cloud.dcompany.erp"
    compileSdk = 35

    defaultConfig {
        applicationId = "cloud.dcompany.erp"
        minSdk = 26
        targetSdk = 35
        // Every Room schema change must ship under a strictly newer Android
        // version code so an installed tablet upgrades in place instead of
        // requiring an uninstall that would destroy its offline outbox.
        versionCode = 18
        versionName = "3.1.7"
        buildConfigField("boolean", "DIRECT_UPDATES_ENABLED", "false")
        buildConfigField("String", "DISTRIBUTION_CHANNEL", buildConfigString("play"))

        // Single source of truth for the API base, mirroring how the
        // Capacitor build takes it from VITE_API_URL at build time.
        buildConfigField("String", "API_BASE_URL", buildConfigString(productionApiBaseUrl))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Debug-only escape hatch for isolated emulator acceptance tests.
            // Release builds remain pinned to the production HTTPS endpoint.
            buildConfigField("String", "API_BASE_URL", buildConfigString(debugApiBaseUrl))
            buildConfigField("String", "DISTRIBUTION_CHANNEL", buildConfigString("managed"))
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("directRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "DIRECT_UPDATES_ENABLED", "true")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", buildConfigString("direct"))
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Never shipped. This gives physical-device QA release-like runtime
        // behaviour without pointing a test APK at live business data. It is
        // selected only with -Pdcompany.androidTestBuildType=physicalAudit.
        create("physicalAudit") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".physicalaudit"
            versionNameSuffix = "-physical-audit"
            buildConfigField(
                "String",
                "API_BASE_URL",
                buildConfigString("https://invalid.dcompany.test/api/v1/"),
            )
            buildConfigField("boolean", "DIRECT_UPDATES_ENABLED", "false")
            // Reuse the already-supported managed-client wire value. The
            // isolated application id and invalid HTTPS endpoint distinguish
            // this QA build without weakening the production API contract.
            buildConfigField("String", "DISTRIBUTION_CHANNEL", buildConfigString("managed"))
        }
    }

    // Debug remains the normal developer/CI target. Physical QA opts into the
    // isolated release-like target explicitly, so ordinary builds do not
    // silently change test behaviour.
    testBuildType = androidTestBuildType

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // MigrationTestHelper reads the same Room-generated history that ships in
    // source control. Point instrumentation directly at that canonical folder
    // so a new schema version cannot be generated successfully yet omitted
    // from tests by a forgotten manual copy into src/androidTest/assets.
    sourceSets.getByName("androidTest").assets.setSrcDirs(listOf("$projectDir/schemas"))
}

// Room schema history — needed for MigrationTestHelper to replay old schemas
// against real Migration objects. ErpDatabase forbids destructive fallback
// because it holds captured sales that exist nowhere else until synced, so a
// wrong migration is a hard crash on every installed device, not a lint nit.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Networking against the existing FastAPI backend.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Token persistence.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Offline store. Room is the local source of truth the UI reads from; the
    // network only fills it and drains the outbox.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Compose UI Test 1.7.5 still brings Espresso 3.5.0 transitively. That
    // release reflectively calls InputManager.getInstance(), which was removed
    // on Android 16/API 36, so every physical Pixel Tablet test fails before
    // its body runs. Espresso 3.7.0 uses Context.getSystemService instead.
    // Keep this test-only: it changes neither the partner APK nor production.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    // Critical cashier forms must be exercised as rendered Compose UI.  The
    // database/sync instrumentation suite cannot prove that a staff member can
    // focus a field, enter text, or complete a no-keyboard fallback flow.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("physicalAuditImplementation", "androidx.compose.ui:ui-test-manifest")
}
