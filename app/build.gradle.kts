plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
}

// Enforcing, scoped to what is actually published. Test sources are gitignored by policy and
// never published, so their violations cannot ship and are not gated on here; main and debug are.
//
// standard:function-naming is disabled deliberately, not to make a number green. All seven hits
// are @Composable functions (DocumentBlock, RichText, ...), and PascalCase is the documented
// Compose convention for composables — a composable that returns Unit is not a factory and is
// named like a type on purpose. Renaming them to camelCase would make the code non-idiomatic to
// satisfy a rule that does not model Compose. Every other standard rule stays on.
ktlint {
    version.set("1.8.0")
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("${File.separator}src${File.separator}test${File.separator}") }
        exclude { it.file.path.contains("${File.separator}src${File.separator}androidTest${File.separator}") }
    }
}

android {
    namespace = "com.nirowear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nirowear"
        minSdk = 30
        targetSdk = 37
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
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
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
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
