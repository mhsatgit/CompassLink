plugins {
    id("com.android.application")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "com.compasslink3d"
    compileSdk {
        // Match the platform package installed by setup: platforms/android-37.0.
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.compasslink3d"
        minSdk = 26
        // Runtime behavior upgrades are separate from the build-tool migration.
        targetSdk = 35
        versionCode = 3
        versionName = "1.2-snapshot.2"
    }
}
