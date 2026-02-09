plugins {
    val kotlinVer = "2.3.0"
    kotlin("jvm") version kotlinVer
    kotlin("plugin.serialization") version kotlinVer
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.thomas"
version = "2.12.5"

repositories {
    maven("https://repo.osgeo.org/repository/release/")
    mavenCentral()
    maven("https://repo.matsim.org/repository/matsim")
    maven("https://mvn.topobyte.de")
    maven("https://mvn.slimjars.com")
}

dependencies {
    val matsimVersion = "2025.0"
    val matsimGroup = "org.matsim"
    testImplementation(kotlin("test"))
    implementation("$matsimGroup:matsim:$matsimVersion")
    implementation("$matsimGroup:pt2matsim:25.8")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.3")
    implementation("org.jetbrains.kotlinx:dataframe:1.0.0-Beta4")
    implementation("org.jetbrains.kotlinx:dataframe-arrow:1.0.0-Beta4")
    implementation("org.apache.arrow:arrow-vector:18.3.0")
    implementation("org.apache.arrow:arrow-memory:18.3.0")
    implementation("org.apache.arrow:arrow-memory-netty:18.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.duckdb:duckdb_jdbc:1.4.3.0")
    implementation("org.kobjects.ktxml:core:1.0.0")
}

application {
    mainClass.set("com.thomas.pt.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = application.mainClass
    }
    archiveBaseName.set("dist")
    archiveClassifier.set("")
}

tasks.test {
    useJUnitPlatform()
}

