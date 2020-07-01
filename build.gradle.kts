plugins {
    application
    kotlin("jvm") version "1.3.72"
    kotlin("kapt") version "1.3.72"
    kotlin("plugin.allopen") version "1.3.72"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7")

    implementation("io.micronaut:micronaut-runtime:2.0.0")
    implementation("io.micronaut:micronaut-http-server-netty:2.0.0")
    implementation("ch.qos.logback:logback-classic:1.2.3")

    implementation("io.micronaut.gcp:micronaut-gcp-common:2.0.0")
    implementation("com.google.cloud:google-cloud-monitoring:1.100.1")
    implementation("com.google.cloud:google-cloud-build:2.0.0")

    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.11.0")

    kapt("io.micronaut:micronaut-inject-java:2.0.0")
    kapt("io.micronaut:micronaut-graal:2.0.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.compileKotlin {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
        javaParameters = true
    }
}

application {
    mainClassName = "com.jamesward.cloudbuildnotifier.ServerKt"
}

allOpen {
    annotation("io.micronaut.aop.Around")
}

kapt {
    arguments {
        arg("micronaut.processing.incremental", true)
        arg("micronaut.processing.annotations", "com.jamesward.cloudbuildnotifier.*")
        arg("micronaut.processing.group", "com.jamesward.cloudbuildnotifier")
        arg("micronaut.processing.module", "com.jamesward.cloudbuildnotifier")
    }
}

tasks.withType<JavaExec> {
    jvmArgs = listOf("-XX:TieredStopAtLevel=1", "-Dcom.sun.management.jmxremote")

    if (gradle.startParameter.isContinuous) {
        systemProperties = mapOf(
            "micronaut.io.watch.restart" to "true",
            "micronaut.io.watch.enabled" to "true",
            "micronaut.io.watch.paths" to "src/main"
        )
    }
}
