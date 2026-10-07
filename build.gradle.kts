plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "io.github.yubinshin"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.yubinshin.resubmit.MainKt"
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
