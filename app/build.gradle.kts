plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.grom.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.grom.server"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Two role flavours (F1 step 1):
    //   server29     — the full Grom server role, minSdk 29.
    //   peripheral26 — client/peripheral role only, minSdk 26, never binds a socket.
    flavorDimensions += "role"
    productFlavors {
        create("server29") {
            dimension = "role"
            minSdk = 29
            buildConfigField("boolean", "GROMSERVER", "true")
            versionNameSuffix = "-server29"
        }
        create("peripheral26") {
            dimension = "role"
            minSdk = 26
            buildConfigField("boolean", "GROMSERVER", "false")
            versionNameSuffix = "-peripheral26"
        }
    }

    // TEST-ONLY signing material. Never use these keys for a distributed build.
    signingConfigs {
        create("gromTest") {
            storeFile = rootProject.file("keys/grom-test-same.jks")
            storePassword = "gromtest"
            keyAlias = "grom"
            keyPassword = "gromtest"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("gromTest")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gromTest")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(project(":sdk"))
    testImplementation("junit:junit:4.13.2")
}
