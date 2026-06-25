plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Exposes the Rova static-gate task type to the app's buildscript classpath.
// Applying `id("com.aritr.rova.checks")` in app/build.gradle.kts is what brings
// SourceCheckTask onto the classpath so the inline tasks.register<SourceCheckTask>
// registrations compile. The plugin itself does NO wiring — gate registrations
// stay inline in app/build.gradle.kts (owner decision Q2).
gradlePlugin {
    plugins {
        create("rovaChecks") {
            id = "com.aritr.rova.checks"
            implementationClass = "com.aritr.rova.gradle.RovaChecksPlugin"
        }
    }
}
