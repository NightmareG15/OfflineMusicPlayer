plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Identificador do commit do GitHub que gerou este APK (os 7 primeiros caracteres).
// No GitHub Actions a variável GITHUB_SHA já existe; numa compilação local aparece "local".
val commitId: String = (System.getenv("GITHUB_SHA") ?: "local").take(7)

android {
    namespace = "com.nightmare.offlinemusic"
    compileSdk = 35

    // Keep Java and Kotlin bytecode targets aligned to avoid Gradle JVM-target errors.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Chave fixa: todas as versões (debug e release) são assinadas com a mesma chave,
    // então uma versão nova instala por cima da anterior sem apagar os dados do app.
    signingConfigs {
        create("veyra") {
            storeFile = file("signing/veyra.jks")
            storePassword = "veyra-player"
            keyAlias = "veyra"
            keyPassword = "veyra-player"
        }
    }

    defaultConfig {
        applicationId = "com.nightmare.offlinemusic"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
        buildConfigField("String", "COMMIT_ID", "\"$commitId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("veyra")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("veyra")
            // Versão enxuta: remove código e recursos que não são usados (APK menor, usa menos memória).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}
