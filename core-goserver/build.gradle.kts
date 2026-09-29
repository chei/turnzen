// Kotlin Multiplatform, like :core-game: the interfaces and model compile for the JVM and
// for iOS, so commonMain may not use java.* APIs (times are kotlin.time.Instant/Duration).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Shared value types (Point, StoneColor, GameRecord) come from the game core so
            // that every server client and the UI speak the same board vocabulary.
            api(project(":core-game"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
