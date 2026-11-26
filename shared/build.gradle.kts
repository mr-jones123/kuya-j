// Everything that isn't tied to Android: the chat logic, search, safety rules and the whole UI.
// The Android app supplies the model, the downloads and the record files through KuyaServices.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    androidLibrary {
        namespace = "ph.bettergov.kuyaj.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    // Compiled (not linked) so common code stays iOS-ready; an iOS app target comes later.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
            implementation("org.jetbrains.compose.ui:ui:1.12.2")
            implementation("org.jetbrains.compose.components:components-resources:1.12.2")
            // Compose Unstyled: behaviour and accessibility only, styled here. Only the modules in use.
            val unstyled = "2.10.2"
            implementation("com.composables:composeunstyled-theming:$unstyled")
            implementation("com.composables:composeunstyled-button:$unstyled")
            implementation("com.composables:composeunstyled-icon:$unstyled")
            implementation("com.composables:composeunstyled-text-field:$unstyled")
            implementation("com.composables:composeunstyled-modal-bottom-sheet:$unstyled")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "ph.bettergov.kuyaj.resources"
}
