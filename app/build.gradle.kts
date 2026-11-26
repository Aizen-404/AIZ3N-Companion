
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val aiz3nKeystore = System.getenv("AIZ3N_KEYSTORE_PATH")
val aiz3nStorePassword = System.getenv("AIZ3N_STORE_PASSWORD")
val aiz3nKeyAlias = System.getenv("AIZ3N_KEY_ALIAS")
val aiz3nKeyPassword = System.getenv("AIZ3N_KEY_PASSWORD")

android {
    namespace = "com.nnexe.overlaybrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nnexe.overlaybrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("aiz3n") {
            if (!aiz3nKeystore.isNullOrBlank()) {
                storeFile = file(aiz3nKeystore)
                storePassword = aiz3nStorePassword
                keyAlias = aiz3nKeyAlias
                keyPassword = aiz3nKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (!aiz3nKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("aiz3n")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("aiz3n")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
