import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing credentials live outside version control (see keystore.properties.example
// for the format) - this file only exists on machines that actually need to produce a signed
// release build, so a plain debug build still works with none of this present.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.mhmh2.bujirun"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mhmh2.bujirun"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.2"

        // NAVER Cloud Platform Maps client ID. Not a secret: it ships inside the app and only
        // works for the package name registered with it (com.mhmh2.bujirun). The client
        // *secret* is never used by the app and must not be committed.
        manifestPlaceholders["naverMapClientId"] = "y7j83b46cr"

        // The Bujirun API (backend/). Override for a local server on the emulator with
        // `-PbujirunApi=http://10.0.2.2:8100/` (cleartext is allowed for 10.0.2.2 only).
        val api = (project.findProperty("bujirunApi") as String?) ?: "https://bujirun.184-193-203-68.sslip.io/"
        buildConfigField("String", "API_BASE", "\"$api\"")

        // Google sign-in: the OAuth *web* client ID the ID token is issued for (the server
        // checks it as BUJIRUN_GOOGLE_CLIENT_ID). Public, like the NAVER ID. `-PgoogleWebClientId=` (empty) hides the button.
        val googleClient = (project.findProperty("googleWebClientId") as String?)
            ?: "865652175610-pfqom7oppf92bob56eb5c19ac7el80p3.apps.googleusercontent.com"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClient\"")
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        // Test APKs go to phones through chat (30 MiB limit): R8 drops the unused library code
        // (the full icon set alone is megabytes), about 20 MB of dex otherwise.
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // The NAVER map SDK ships ~23 MB of native code per CPU type. For sideloaded test APKs:
    // one APK per CPU type (phones are arm64-v8a, the emulator x86_64) with the native libraries
    // compressed, so a phone APK stays small enough to send through chat. Play releases are
    // AABs, which Play splits per device anyway; these settings don't affect them.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // AGP 8.7.2's bundled lint crashes on this detector with this Kotlin version (a tooling
        // bug, not a real issue) - same workaround as EnglishBite.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.play.services.location)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.health.connect)
    implementation(libs.naver.map)
    implementation(libs.work.runtime)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.google.id)
    testImplementation(libs.junit)
}

// Debug packaging updates the APK in place and leaves the space of replaced entries behind:
// a phone APK grew to twice its real size (31 MB for 14.5). So each debug package starts from
// nothing - the APKs *and* the packager's incremental state removed by a task before it, so
// Gradle sees its outputs gone and packages in full. (Deleting only the APKs inside the task
// itself made it write just the changed entries into an empty file: a 2-entry, broken APK.)
val freshDebugApk = tasks.register<Delete>("freshDebugApk") {
    delete(layout.buildDirectory.dir("outputs/apk/debug"), layout.buildDirectory.dir("intermediates/incremental/packageDebug"))
}
tasks.matching { it.name == "packageDebug" }.configureEach { dependsOn(freshDebugApk) }
