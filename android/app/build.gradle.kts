plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: GitHub secrets if present (C3TRX_KEYSTORE_B64 decoded to a file by the workflow),
// otherwise the public project key in this folder, so successive releases install over each other.
val ksFile = System.getenv("C3TRX_KEYSTORE")?.let { file(it) } ?: file("c3trx-public.keystore")
val ksPass = System.getenv("C3TRX_KEYSTORE_PASSWORD") ?: "c3trx-public"
val ksAlias = System.getenv("C3TRX_KEY_ALIAS") ?: "c3trx"
val ksKeyPass = System.getenv("C3TRX_KEY_PASSWORD") ?: ksPass

android {
    namespace = "gr.sv1eex.c3trx"
    compileSdk = 35

    defaultConfig {
        applicationId = "gr.sv1eex.c3trx"
        minSdk = 29          // Android 10
        targetSdk = 34
        versionCode = (System.getenv("C3TRX_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("C3TRX_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksKeyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
