plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // If you were using kapt for libraries like Dagger or Room, you would add:
    // id("kotlin-kapt")
}

android {
    namespace = "com.example.sosrelay" // Ensure this matches your project's actual namespace
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.sosrelay"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // You can add a debug build type if you need specific configurations for it
        // debug {
        //     applicationIdSuffix = ".debug" // Example
        // }
    }

    // Java version compatibility
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Kotlin options (ensure this matches your Kotlin plugin version's capabilities)
    kotlinOptions {
        jvmTarget = "1.8" // For Kotlin 1.9.x. If using Kotlin 2.0.x with compatible AGP, could be "11" or "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Reference dependencies from libs.versions.toml
    // Ensure these aliases (kotlin.stdlib, androidx.core.ktx, etc.)
    // match what's defined in your gradle/libs.versions.toml file.
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.android.gms:play-services-location:21.2.0")
// Or the l
    implementation(libs.kotlin.stdlib) // Example: kotlinStdlib = "1.9.23" in toml
    implementation(libs.androidx.core.ktx) // Example: coreKtx = "1.12.0" in toml
    implementation(libs.androidx.appcompat) // Example: appcompat = "1.6.1" in toml
    implementation(libs.material) // Example: material = "1.11.0" in toml
    implementation(libs.androidx.constraintlayout) // Example: constraintlayout = "2.1.4" in toml
    implementation(libs.kotlinx.coroutines.android) // Example: kotlinxCoroutinesAndroid = "1.7.3" in toml

    // Standard test dependencies (uncomment and ensure aliases exist in TOML if needed)
    // testImplementation(libs.junit)
    // androidTestImplementation(libs.androidx.junit) // Alias for androidx.test.ext:junit
    // androidTestImplementation(libs.androidx.espresso.core)
}
