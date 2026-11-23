/*
 * Glassé (Liquid Glass for Jetpack Compose), vendored from
 * https://github.com/BenItBuhner/Glasse — packages/android/liquidglass, MIT License.
 *
 * Built here against the phone app's own Compose rather than pulled from JitPack,
 * so depending on it never moves the app's Compose underneath it (the published
 * AAR is compiled against a newer BOM). See glasse/README.md for the upstream
 * commit and how to refresh it.
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.benitbuhner.liquidglass"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
android.testOptions.unitTests.isReturnDefaultValues = true
