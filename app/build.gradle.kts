plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nirowear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nirowear"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.0-beta.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] = "com.nirowear.HostDrivenGate"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes.named("debug") {
        val iconPreview = providers.gradleProperty("iconPreview").orNull == "true"
        if (iconPreview) applicationIdSuffix = ".iconpreview"
        manifestPlaceholders["debugAppLabel"] = if (iconPreview) "NiroWear Icon Preview" else "NiroWear"
    }

    buildTypes.named("release") {
        optimization { enable = true }
    }

    sourceSets["debug"].assets.directories.add(file("../fixtures").path)
    sourceSets["androidTest"].assets.directories.add(file("../fixtures").path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    testImplementation("junit:junit:4.13.2")
    // GPL v2-or-later WITH an independent-module linking exception: the exception permits linking
    // with MIT code and distributing the executable under terms of our choice. Notices + source
    // offer ship in assets/legal; see THIRD_PARTY_NOTICES.md.
    implementation("ru.noties:jlatexmath-android:0.2.0")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
