// The Android app: model runtime (LiteRT-LM), model download, signed record sync and the built-in records.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun prop(name: String): String = providers.gradleProperty(name).getOrElse("").trim()

android {
    namespace = "ph.bettergov.kuyaj"
    compileSdk = 37

    defaultConfig {
        applicationId = "ph.bettergov.kuyaj"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SYNC_BASE_URL", "\"${prop("kuyaj.syncBaseUrl")}\"")
        buildConfigField("String", "SYNC_PUBLIC_KEYS", "\"${prop("kuyaj.syncPublicKeys")}\"")
        // LiteRT-LM ships native code for these two ABIs only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.compose.ui:ui:1.12.2")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.20.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    // android.jar only stubs org.json; unit tests need the real one.
    testImplementation("org.json:json:20260719")
}

// The tests read the shipped records from disk; re-run them whenever the records change.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets/kb")
    // The conversation eval talks to a model server, so it always re-runs when asked for (see ConversationEval).
    outputs.upToDateWhen { System.getenv("KUYAJ_EVAL_URL") == null }
}
