plugins {
    java
    // ./gradlew runServer: a real Paper test server in ./run with this plugin loaded.
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "xyz.merlionmchost"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/") // GeyserMC (Floodgate API)
}

// The Paper build MerlionMC servers run (checked 2026-11-23). Bump together with the servers.
val paperApi = "26.3.build.259-stable"
val minecraft = "26.3"
// Loaded by Paper's library loader at runtime (plugin.yml "libraries"), so it's compileOnly here.
val bouncycastle = "1.87"
// The SQLite driver Paper 26.3 ships (found in its libraries folder); tests use the same one.
val sqliteJdbc = "3.49.1.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("org.bouncycastle:bcprov-jdk18on:$bouncycastle")
    // The Floodgate MerlionMC servers run (v2.2.5-SNAPSHOT); optional at runtime (softdepend).
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    // The logging Paper 26.3 ships (its libraries folder), for the filter that hides passwords.
    compileOnly("org.apache.logging.log4j:log4j-core:2.26.0")
    testImplementation("org.apache.logging.log4j:log4j-core:2.26.0")

    testImplementation("io.papermc.paper:paper-api:$paperApi")
    testImplementation("org.bouncycastle:bcprov-jdk18on:$bouncycastle")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteJdbc")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Paper 26.x runs on Java 25 (MerlionMC uses itzg/minecraft-server:java25).
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 25
    }
    processResources {
        val props = mapOf("version" to project.version, "minecraft" to minecraft, "bouncycastle" to bouncycastle)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
    test {
        useJUnitPlatform()
    }
    runServer {
        minecraftVersion(minecraft)
        // 25565 is taken by the local MerlionMC gateway; join the test server at localhost:25590.
        args("--port", "25590", "--nogui")
        jvmArgs("-Dcom.mojang.eula.agree=true")
        // Geyser and Floodgate (like a MerlionMC server) sit in run/plugins, downloaded once with curl and checked against
        // GeyserMC's SHA-256: GeyserMC's download server refuses run-paper's own downloader (HTTP 406).
    }
}
