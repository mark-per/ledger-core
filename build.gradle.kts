plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "ledger"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("known-failing")
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Test>("knownFailing") {
    description = "Runs the one test that is expected to fail against the current design."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("known-failing")
    }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<JavaExec>("replay") {
    description = "Replays the six-day event stream and prints the per-day report."
    group = "application"
    mainClass.set("ledger.MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
