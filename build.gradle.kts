plugins {
    kotlin("jvm") version "2.1.10"
}

group = "pt.paulinoo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.lavalink.dev/releases")
}

dependencies {
    implementation("dev.arbjerg:lavaplayer:2.2.3")
    implementation("net.dv8tion:JDA:5.3.0")
    implementation("se.michaelthelin.spotify:spotify-web-api-java:9.1.1")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("dev.lavalink.youtube:v2:1.11.5")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}