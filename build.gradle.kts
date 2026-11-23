plugins {
  kotlin("jvm") version "2.3.10"
  kotlin("plugin.serialization") version "2.3.10"
  id("org.jetbrains.compose") version "1.12.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
  id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "solo.music"
version = "0.1.0"

// Compile to Java 21 bytecode while using any installed JDK 21 or newer.
// This keeps the app compatible with the minimum runtime without requiring
// a separate JDK 21 installation on newer-JDK development machines.
val minimumJavaVersion = 21
val installedJavaVersion = JavaVersion.current().majorVersion.toInt()
val compilerJavaVersion = maxOf(minimumJavaVersion, installedJavaVersion)
val buildWindowsSmtcHelper = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val windowsSmtcResourceDir = layout.buildDirectory.dir("generated/windows-smtc-resources")

kotlin {
  jvmToolchain(compilerJavaVersion)
  compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(minimumJavaVersion)
}

javafx {
  // 23.0.2 ships Linux libavplugins for libavcodec 57-61, so media playback works on
  // Ubuntu 24.04+ / Fedora / Debian where JavaFX 21 could not create a player at all.
  version = "23.0.2"
  modules("javafx.controls", "javafx.media")
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
  implementation("net.jthink:jaudiotagger:3.0.1")
  implementation("net.java.dev.jna:jna:5.17.0")
  testImplementation(kotlin("test"))
}

tasks.test { useJUnit() }

if (buildWindowsSmtcHelper) {
  sourceSets.main { resources.srcDir(windowsSmtcResourceDir) }

  val compileWindowsSmtcLibrary = tasks.register<Exec>("compileWindowsSmtcLibrary") {
    val outputDir = layout.buildDirectory.dir("native/smtc-bridge")
    inputs.files(fileTree("native/smtc-bridge") { include("**/*.cpp", "**/*.bat") })
    outputs.file(outputDir.map { it.file("SoloSmtcBridge.dll") })
    doFirst { outputDir.get().asFile.mkdirs() }
    commandLine("cmd.exe", "/c", file("native/smtc-bridge/build.bat").absolutePath, outputDir.get().asFile.absolutePath)
  }

  val stageWindowsSmtcLibrary = tasks.register<Copy>("stageWindowsSmtcLibrary") {
    dependsOn(compileWindowsSmtcLibrary)
    doFirst { windowsSmtcResourceDir.get().asFile.resolve("native").deleteRecursively() }
    from(layout.buildDirectory.file("native/smtc-bridge/SoloSmtcBridge.dll"))
    into(windowsSmtcResourceDir.map { it.dir("native") })
  }

  tasks.named("processResources") { dependsOn(stageWindowsSmtcLibrary) }
}

compose.desktop {
  application {
    mainClass = "solo.music.app.MainKt"
    nativeDistributions {
      modules(
        "java.base", "java.datatransfer", "java.desktop", "java.instrument",
        "java.logging", "java.net.http", "java.prefs", "java.xml",
        "jdk.crypto.ec", "jdk.jfr", "jdk.unsupported"
      )
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
      )
      packageName = "Solo Music Player"
      packageVersion = project.version.toString()
      description = "A focused desktop music player"
      vendor = "Solo Music"
      windows {
        iconFile.set(project.file("assets/solo_music_icon.ico"))
        menuGroup = "Solo Music"
        shortcut = true
        dirChooser = true
        perUserInstall = true
        upgradeUuid = "4D0EFDB6-5B91-4E2A-88EC-B1E684D91A53"
      }
      linux {
        // Debian package names must be lowercase without spaces.
        packageName = "solo-music-player"
        iconFile.set(project.file("assets/solo_music_icon_source.png"))
        menuGroup = "Solo Music"
        shortcut = true
      }
    }
  }
}
