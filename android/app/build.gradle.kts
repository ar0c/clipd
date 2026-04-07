plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clipd"
    compileSdk = 36
    compileSdkExtension = 18

    val legacyDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")

    signingConfigs {
        if (legacyDebugKeystore.exists()) {
            create("legacyDebug") {
                storeFile = legacyDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.clipd"
        minSdk = 26
        targetSdk = 36
        versionCode = 85
        versionName = "1.0.85"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("legacyDebug") ?: signingConfig
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.lifecycle:lifecycle-livedata-core:2.6.1")
        force("androidx.lifecycle:lifecycle-livedata:2.6.1")
        force("androidx.customview:customview:1.1.0")
        force("androidx.activity:activity:1.8.0")
        force("androidx.drawerlayout:drawerlayout:1.1.1")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
