// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the kotlin-android plugin is not
// applied here. Only the Compose compiler plugin is needed on top.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// `./gradlew :app-android:assembleDebug -Pturnzen.fakeServer=true` builds a debug APK that
// talks to the in-memory demo server instead of DGS (useful on an emulator without an account).
val useFakeServer = providers.gradleProperty("turnzen.fakeServer").orNull == "true"

android {
    namespace = "dev.turnzen.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.turnzen"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Public project links shown on the Settings screen.
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/OWNER/turnzen\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "USE_FAKE_SERVER", useFakeServer.toString())
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
    // The UI depends on the server-agnostic interfaces. The concrete DGS client is only
    // referenced from the app's composition root (AppContainer), never from screens.
    implementation(project(":core-goserver"))
    implementation(project(":core-game"))
    implementation(project(":core-dgs"))

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
