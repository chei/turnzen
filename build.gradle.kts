// Root build: declares plugin versions (applied per module) and the ktlint tasks.
//
// ktlint is run through its CLI in a JavaExec task instead of a Gradle plugin, so it
// stays independent of the Android Gradle Plugin's DSL changes. `./gradlew ktlintCheck`
// reports violations; `./gradlew ktlintFormat` fixes what it can.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Every Kotlin Multiplatform module (the :core-* ones) gets two shorthand tasks:
// `test` runs its tests on the JVM, as `test` does in a plain JVM module, and `compileIos`
// compiles its iOS targets. The latter works on Linux, since Kotlin cross-compiles iOS
// libraries without a Mac, so CI catches JVM-only calls in shared code before anyone builds
// the iOS app.
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        tasks.register("test") {
            group = "verification"
            description = "Runs the tests on the JVM."
            dependsOn("jvmTest")
        }
        tasks.register("compileIos") {
            group = "build"
            description = "Compiles the iOS targets."
            dependsOn("compileKotlinIosArm64", "compileKotlinIosSimulatorArm64")
        }
    }
}

val ktlint: Configuration by configurations.creating

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
}

val ktlintPatterns =
    listOf(
        "**/src/**/*.kt",
        "**/*.kts",
        "!**/build/**",
    )

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks Kotlin sources with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(ktlintPatterns)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Formats Kotlin sources with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("-F") + ktlintPatterns)
}
