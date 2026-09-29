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

// Keeps `./gradlew :<module>:test` working as it does for the JVM-only modules.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests on the JVM."
    dependsOn("jvmTest")
}

// Compiles the iOS targets. Runs on Linux too: Kotlin cross-compiles iOS libraries without a Mac,
// so CI catches JVM-only API use in common code before anyone builds the iOS app.
tasks.register("compileIos") {
    group = "build"
    description = "Compiles the iOS targets."
    dependsOn("compileKotlinIosArm64", "compileKotlinIosSimulatorArm64")
}
