// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the kotlin-android plugin is not
// applied here. Only the Compose compiler plugin is needed on top.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// `./gradlew :app-android:assembleDebug -Pturnzen.fakeServer=true` builds a debug APK that
// talks to the in-memory demo server instead of DGS (useful on an emulator without an account).
val useFakeServer = providers.gradleProperty("turnzen.fakeServer").orNull == "true"

// The OGS OAuth application's client id, registered under the maintainer's OGS account.
// Public by design: the sign-in uses PKCE, so there is no secret to keep. A fork registering
// its own application can override it with `-Pturnzen.ogsClientId=...`.
val ogsClientId = providers.gradleProperty("turnzen.ogsClientId").orNull ?: "yK58MvJ9o8FP75ri6Z6sv1PS8jLBIODWIKAXXmpz"

android {
    namespace = "dev.turnzen.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.chei.turnzen"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Public project links shown on the Settings screen.
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/chei/turnzen\"")
        buildConfigField("String", "OGS_CLIENT_ID", "\"$ogsClientId\"")
    }

    // Debug builds are signed with a key committed to the repo rather than each machine's own
    // ~/.android/debug.keystore, so an APK from CI and one from any laptop are the same app to
    // Android and install over each other, keeping the app's data. The key signs nothing but
    // test builds of Turnzen, so publishing it costs nothing; release signing stays out of the
    // repo. See app-android/signing/README.md.
    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/turnzen-debug.keystore")
            storePassword = "android"
            keyAlias = "turnzen-debug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "USE_FAKE_SERVER", useFakeServer.toString())
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            buildConfigField("boolean", "USE_FAKE_SERVER", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // The UI depends on the server-agnostic interfaces. The concrete DGS and OGS clients are
    // only referenced from the app's composition root (AppContainer), never from screens.
    implementation(project(":core-goserver"))
    implementation(project(":core-game"))
    implementation(project(":core-dgs"))
    implementation(project(":core-ogs"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
