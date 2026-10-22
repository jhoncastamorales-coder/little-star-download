import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.socialgrab.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.socialgrab.downloader"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // lintVitalRelease crashea internamente en este proyecto (no es un
        // problema de nuestro codigo). No es relevante para uso personal
        // sideloaded, asi que se desactiva en vez de bloquear el build.
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            // Necesario para poder ejecutar libqjs.so como binario desde
            // applicationInfo.nativeLibraryDir en tiempo de ejecucion: sin esto
            // AGP puede dejar las librerias nativas sin extraer a disco.
            useLegacyPackaging = true
        }
    }
}

// Version de Python embebida. Chaquopy 17.0.0 solo soporta 3.10-3.14 (3.15,
// que es lo unico que el launcher `py` detecta en esta PC, no esta soportado
// todavia) -- se usa 3.13, instalada aparte via uv, indicandole la ruta exacta
// a Chaquopy porque el launcher `py -3.13` no la detecta (no es una instalacion
// "estandar" de python.org).
chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("C:/Users/jhonx/AppData/Roaming/uv/python/cpython-3.13.16-windows-x86_64-none/python.exe")
        pip {
            install("yt-dlp")
            install("yt-dlp-ejs")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 1.11.1 exige compileSdk 36+; se usa una version mas vieja para no
    // arrastrar una actualizacion de AGP solo por esto.
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
