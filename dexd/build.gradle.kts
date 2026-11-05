// "dexd": the on-phone DeX helper. Built as a tiny APK whose classes.dex is run with app_process
// under the shell user (started once per boot through Wireless debugging, see tools/rokid-dex.sh).
// Plain Java, no dependencies, so the dex stays small and needs nothing from the app.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.eberhard.rokidmirror.dexd"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.eberhard.rokidmirror.dexd"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
