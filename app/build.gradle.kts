@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {

    implementation(fileTree("libs"))

    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.9.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.browser:browser:1.5.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.preference:preference-ktx:1.2.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.work:work-multiprocess:2.8.1")

    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("com.google.android.material:material:1.13.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.google.code.gson:gson:2.9.0")

    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.camera:camera-camera2:1.4.2")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.4")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.jakewharton:process-phoenix:2.1.2")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.esotericsoftware:kryo:5.2.1")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.ini4j:ini4j:0.5.4")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.room:room-runtime:2.6.1")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    ksp("androidx.room:room-compiler:2.6.1")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    testImplementation("junit:junit:4.13.2")
    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection NewerVersionAvailable
    testImplementation("org.json:json:20240303")

    // Version pinned for SDK 35 / current Kotlin and serialized config compatibility; see docs/LINT_REVIEW.md.
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
