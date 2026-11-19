plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.grom.testapp.foreigncert"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.grom.testapp.foreigncert"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("gromForeign") {
            storeFile = rootProject.file("keys/grom-test-foreign.jks")
            storePassword = "gromtest"
            keyAlias = "grom"
            keyPassword = "gromtest"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("gromForeign") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gromForeign")
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
