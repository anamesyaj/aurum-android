plugins {
    id("com.android.application")
}

val sourceSha = providers.environmentVariable("GITHUB_SHA").orElse("local").get()

android {
    namespace = "ai.aurum.personal"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.aurum.personal"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0-a3"
        buildConfigField("String", "GIT_SHA", "\"$sourceSha\"")
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    buildTypes {
        getByName("debug") {
            // A3 debug builds may reach a trusted private-LAN Aurum Core over HTTP.
            // BackendConfig rejects public cleartext hosts in application code.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
