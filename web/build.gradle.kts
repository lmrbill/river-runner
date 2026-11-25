import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Web version of River Runner for iPhone and other browsers. Built twice: Wasm for current
// browsers (Safari 18.2+) and JS for older ones; `composeCompatibilityBrowserDistribution`
// packs both with a small loader that picks the one the browser supports.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

/**
 * BuildInfo.kt with the version and CHANGELOG.md, which the Android app reads from its APK assets.
 * The version is the top "## X.Y" of CHANGELOG.md plus the CI run number, as for the APK.
 */
abstract class GenerateBuildInfo : DefaultTask() {
    @get:InputFile
    abstract val changelog: RegularFileProperty

    @get:Input
    abstract val runNumber: Property<String>

    /** Commit the site is built from (GITHUB_SHA in CI). */
    @get:Input
    abstract val commit: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = changelog.get().asFile.readText()
        val base = Regex("""(?m)^## (\d+\.\d+)""").find(text)?.groupValues?.get(1) ?: "0.0"
        fun literal(s: String) = buildString {
            append('"')
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> {}
                else -> append(c)
            }
            append('"')
        }
        val dir = outputDir.get().asFile.resolve("com/riverrunner/web")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            "package com.riverrunner.web\n\n// Generated from CHANGELOG.md by web/build.gradle.kts\n" +
                "internal object BuildInfo {\n" +
                "    const val VERSION = " + literal(base + "." + runNumber.get()) + "\n" +
                "    const val COMMIT = " + literal(commit.get().take(7)) + "\n" +
                "    const val BUILD_DATE = " + literal(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()) + "\n" +
                "    val CHANGELOG: String = " + literal(text) + "\n}\n"
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    runNumber.set(providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("0"))
    commit.set(providers.environmentVariable("GITHUB_SHA").orElse("local"))
    outputDir.set(layout.buildDirectory.dir("generated/buildInfo"))
}

kotlin {
    js {
        browser { commonWebpackConfig { outputFileName = "riverRunner.js" } }
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "riverRunner.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            implementation(project(":engine"))
            implementation(project(":ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "com.riverrunner.web.res"
}
