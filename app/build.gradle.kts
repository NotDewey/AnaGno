plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization") version "1.9.24"
}

// Resolve the old DeepPanel AAR only as a build-time source for its trained model.
// It is NOT added to the runtime classpath, so its old 4 KB native libraries never
// enter Anagno's APK.
val deepPanelModelAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val generatedDeepPanelResources =
    layout.buildDirectory.dir("generated/deeppanelModel/res")

val extractDeepPanelModel by tasks.registering(Sync::class) {
    from({
        deepPanelModelAar.files.map { aar -> zipTree(aar) }
    }) {
        include("res/raw/model.tflite")
        eachFile {
            path = "raw/deep_panel_model.tflite"
        }
        includeEmptyDirs = false
    }

    into(generatedDeepPanelResources)
}

android {
    namespace = "com.comicreader.app"
    compileSdk = 34

    sourceSets.getByName("main").res.srcDir(generatedDeepPanelResources)

    defaultConfig {
        applicationId = "com.comicreader.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Backdrop blur for the floating Android 17-style navigation dock.
    implementation("dev.chrisbanes.haze:haze:0.7.3")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Zip / CBZ extraction (Apache Commons Compress handles zip robustly incl. odd encodings)
    implementation("org.apache.commons:commons-compress:1.26.2")

    // RAR4/RAR5 / CBR reading through native libarchive (no full archive copy)
    implementation("me.zhanghai.android.libarchive:library:1.1.6")

    // DataStore for simple prefs (last-read page, theme, etc. beyond Room)
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // DeepPanel's original trained model only. The AAR is unpacked at build time;
    // none of its old native .so files or TensorFlow Lite 2.2.0 dependency are packaged.
    deepPanelModelAar("com.github.pedrovgs:deeppanel:0.0.1@aar")

    // Current stable LiteRT 1.x Interpreter runtime. Keep the API artifact
    // explicit as well: the local DeepPanel compatibility layer imports
    // org.tensorflow.lite.Interpreter, while the runtime artifact supplies
    // the native CPU implementation.
    //
    // Deliberately do NOT add litert-support: it is unnecessary here.
    implementation("com.google.ai.edge.litert:litert-api:1.4.2")
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    // On-device speech-balloon instance segmentation.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    // Bundled on-device OCR: immediately available and used to validate/split
    // dialogue regions without uploading comic pages or downloading at runtime.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Resumable background panel analysis (2.9.x remains compatible with compileSdk 34)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// The generated R.raw.deep_panel_model must exist before Android merges app resources.
tasks.named("preBuild").configure {
    dependsOn(extractDeepPanelModel)
}
