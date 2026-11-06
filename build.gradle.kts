import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.github.hbymrito"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")

    intellijPlatform {
        val localIdea = file("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app")
        if (localIdea.exists()) {
            local(localIdea)
        } else {
            intellijIdea("2026.3")
        }
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        jvmTarget = JvmTarget.JVM_25
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
        }
    }

    buildSearchableOptions = false

    pluginVerification {
        ides {
            local("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    from("LICENSE") {
        into("META-INF")
    }
}
