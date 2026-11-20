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
        versionCode = 4
        versionName = "0.4.0"
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

    // D34: the Node runtime is executed from the APK's native library dir, so
    // the libraries must be extracted to disk at install time. This is the AGP 8
    // way to set android:extractNativeLibs="true"; setting the manifest
    // attribute directly is deprecated (and lint warns about it).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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

/**
 * Copies the canonical agent script into a generated assets directory.
 *
 * It is registered through `variant.sources.assets.addGeneratedSourceDirectory`
 * rather than wired by task name, so every consumer (merge assets, packaging
 * and lint's model writer) gets a declared dependency automatically. Pattern
 * matching on task names is what produced an implicit-dependency failure.
 */
abstract class SyncAgentRuntime : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun sync() {
        // The directory is an assets root, so the agent lands at node/<name>.
        val target = outputDirectory.get().asFile.resolve("node/grom-agent.js")
        target.parentFile.mkdirs()
        sourceFile.get().asFile.copyTo(target, overwrite = true)
    }
}

val syncAgentRuntime = tasks.register<SyncAgentRuntime>("syncAgentRuntime") {
    sourceFile.set(rootProject.layout.projectDirectory.file("agent/grom-agent.js"))
    outputDirectory.set(layout.buildDirectory.dir("generated/agent-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncAgentRuntime) { it.outputDirectory }
    }
}

dependencies {
    implementation(project(":sdk"))
    testImplementation("junit:junit:4.13.2")
}
