// Kotlin Multiplatform: the same sources compile for the JVM (used by the Android app and
// the JVM tests) and to native code for iOS. Everything lives in commonMain, so nothing here
// may use java.* APIs; the tests in commonTest run on the JVM.
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
