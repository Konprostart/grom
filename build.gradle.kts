// Grom — root build. See docs/000-decisions.md and docs/F1-implementation-plan.md.
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("com.android.library") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
