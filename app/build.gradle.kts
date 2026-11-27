plugins {
    id("com.android.application")
}

val commitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "kg.home.kino"
    compileSdk = 36

    defaultConfig {
        applicationId = "kg.home.kino"
        minSdk = 23          // Skyworth 43G6 runs Android 6.0.1
        targetSdk = 36
        // Version = number of commits, so every build from a newer commit (local or GitHub Actions)
        // has a higher versionCode and installs over the previous one.
        versionCode = commitCount
        versionName = "1.0.$commitCount"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.github.bumptech.glide:glide:4.16.0")
}
