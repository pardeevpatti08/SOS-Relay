buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Use the AGP version consistent with your libs.versions.toml
        classpath("com.android.tools.build:gradle:8.3.0") // Fixed

        // Use the Kotlin plugin version consistent with your libs.versions.toml
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.23") // Fixed
    }
}