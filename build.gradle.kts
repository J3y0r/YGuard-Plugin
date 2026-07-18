plugins {
    kotlin("jvm") version  "2.4.20-Beta1" 
    id("com.gradleup.shadow") version "9.6.0"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.xerial:sqlite-jdbc:3.53.2.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.mysql:mysql-connector-j:9.7.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("ch.vorburger.mariaDB4j:mariaDB4j-core:3.3.1")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("ch.vorburger.mariaDB4j:mariaDB4j-db-winx64:11.4.5")
}

kotlin {
    jvmToolchain(21)
}

val compileClientInterop by tasks.registering(GradleBuild::class) {
    dir = file("YGuard-ClientSide-Mod")
    tasks = listOf("compileJava")
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        dependencies {
            exclude(dependency("org.slf4j:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
        }
    }

    test {
        dependsOn(compileClientInterop)
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version , "description" to project.description )
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
