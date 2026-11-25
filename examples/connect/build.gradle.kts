plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * A compiled, tested client example (D93).
 *
 * It is a library, not an application, so the example cannot quietly become a
 * second implementation of the server: it depends on `:sdk` exactly the way a
 * host application would, and its test drives the same flow against a fake
 * transport.
 */
android {
    namespace = "app.grom.examples.connect"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":sdk"))
    testImplementation("junit:junit:4.13.2")
}
