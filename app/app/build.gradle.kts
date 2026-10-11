plugins { id("com.android.application") }

// Where the Hermes Desktop renderer bundle lives. Override with
//   gradle assembleRelease -PhermesDesktopDist=/path/to/hermes-agent/apps/desktop/dist
// or the HERMES_DESKTOP_DIST environment variable.
val hermesDesktopDist: String = (findProperty("hermesDesktopDist") as String?)
    ?: System.getenv("HERMES_DESKTOP_DIST")
    ?: "${System.getProperty("user.home")}/.hermes/hermes-agent/apps/desktop/dist"

android {
    namespace = "io.github.hermesmobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.hermesmobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes {
        debug { buildConfigField("boolean", "WEB_DEBUG", "true") }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "WEB_DEBUG", "false")
            // Sideload-friendly default; configure your own keystore for real distribution (README).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += listOf("js", "css", "wasm", "json", "html") }
}

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
}

// Copy the desktop renderer + mobile shim into assets at build time.
val syncRenderer by tasks.registering(Sync::class) {
    doFirst {
        require(file("$hermesDesktopDist/index.html").exists()) {
            "Hermes Desktop renderer not found at $hermesDesktopDist. Build it (cd hermes-agent/apps/desktop && npm run build) or pass -PhermesDesktopDist=..."
        }
    }
    from(hermesDesktopDist) {
        // Build stamps carry absolute paths of the build machine — keep them out of the APK.
        exclude("electron-main.mjs", "electron-preload.js", "**/*.map", "hermes-build.json")
        into("renderer")
    }
    from(rootProject.file("../shim")) { into("shim") }
    into(layout.buildDirectory.dir("generated/rendererAssets"))
}
android.sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/rendererAssets").get().asFile.path)
tasks.named("preBuild") { dependsOn(syncRenderer) }
