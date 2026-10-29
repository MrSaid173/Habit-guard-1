plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.habitguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.habitguard"
        minSdk = 30        // Android 11
        targetSdk = 33     // below 34 on purpose: no FGS-type requirement, fewer background limits
        versionCode = 13
        versionName = "0.4.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
