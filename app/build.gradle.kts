import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }

            create("play") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["uploadKeyAlias"] as String
                keyPassword = keystoreProperties["uploadKeyPassword"] as String
            }
        }
    }

    compileSdk = 37
    buildToolsVersion = "37.0.0"
    // ThunderCam: NDK 25 — единственный целый в SDK (29/27 без source.properties).
    // Совпадает с донором, где этот же CMakeLists собирался.
    ndkVersion = "25.1.8937393"

    namespace = "app.grapheneos.camera"

    defaultConfig {
        applicationId = "app.grapheneos.camera"
        minSdk = 29
        targetSdk = 37
        versionCode = 94
        versionName = versionCode.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ThunderCam native P0 (NEON): только arm64 (бюджетники и флагманы — все arm64)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                //cppFlags += "-O3 -ffast-math" // уже в CMakeLists
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
            resValue("string", "app_name", "ThunderCam")
        }

        getByName("debug") {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "ThunderCam d")
            // isDebuggable = false
        }

        create("play") {
            initWith(getByName("release"))
            applicationIdSuffix = ".play"
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("play")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    implementation(libs.bundles.camerax)

    implementation(libs.zxing.core)

    // ThunderCam P0 pipeline (toggleable, see CamConfig.THMC_P0)
    implementation(libs.opencv)
    implementation(libs.mlkit.face.detection)

    // ThunderCam AI light (Zero-DCE, Apache-2.0): GPU delegate first, CPU fallback
    implementation(libs.tflite.lib)
    implementation(libs.tflite.gpu.lib)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)

    // ThunderCam JVM-тесты чистой логики (ThmcSettings/MapStore, tileGrid, parseWxH)
    testImplementation(libs.junit)
}
