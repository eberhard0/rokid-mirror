import java.util.Properties

plugins {
    id("com.android.application")
}

val signingProps = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

val appVersionName = "1.2.0"
val appVersionCode = 11

base {
    archivesName = "RokidScreen-glasses-$appVersionName"
}

android {
    namespace = "com.eberhard.rokidmirror.glasses"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.eberhard.rokidmirror.glasses"
        // The Rokid Glasses run Android 12 Go (API 32); 29 keeps it installable on older boxes too.
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
}
