plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nightmare.offlinemusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nightmare.offlinemusic"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
