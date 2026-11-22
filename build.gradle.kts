plugins {
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // Compose Multiplatform: the shared UI module (Android + web) and the web build
    id("org.jetbrains.compose") version "1.9.3" apply false
    id("io.github.takahirom.roborazzi") version "1.43.0" apply false
}
