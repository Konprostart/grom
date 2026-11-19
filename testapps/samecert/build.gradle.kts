plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.grom.testapp.samecert"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.grom.testapp.samecert"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("gromSame") {
            storeFile = rootProject.file("keys/grom-test-same.jks")
            storePassword = "gromtest"
            keyAlias = "grom"
            keyPassword = "gromtest"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("gromSame") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gromSame")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":sdk"))
}
