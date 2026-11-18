import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("com.android.library") version "8.7.3"
}

group = "com.magicfriends"
version = "0.1.0"

// Versiones alineadas con las apps (Kotlin 2.0.21, JDK 17, iOS 15). Ktor 3.0.x se compila con Kotlin 2.0.
val ktorVersion = "3.0.3"
val coroutinesVersion = "1.9.0"
val serializationVersion = "1.7.3"

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    // Destino JVM: permite ejecutar los tests en Windows/Linux sin emulador y usar la librería desde herramientas de consola.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MagicFriendsClient"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("io.ktor:ktor-client-websockets:$ktorVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:$ktorVersion") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:$ktorVersion") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-cio:$ktorVersion") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }
    }
}

android {
    namespace = "com.magicfriends.client"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Los MISMOS fixtures que usa el servidor (../protocol/fixtures) se compilan dentro de commonTest como constantes,
// porque commonTest no puede leer ficheros en todas las plataformas.
val fixturesDir = layout.projectDirectory.dir("../protocol/fixtures")
val generatedFixturesDir = layout.buildDirectory.dir("generated/fixtures")
val generateFixtures by tasks.registering {
    inputs.dir(fixturesDir)
    outputs.dir(generatedFixturesDir)
    doLast {
        fun literal(text: String): String = "\"" + text
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\r", "").replace("\n", "\\n") + "\""
        val root = fixturesDir.asFile
        val scenarios = root.resolve("scenarios").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        val out = generatedFixturesDir.get().asFile.resolve("com/magicfriends/client/fixtures")
        out.mkdirs()
        out.resolve("Fixtures.kt").writeText(buildString {
            appendLine("// Generado por Gradle (generateFixtures) a partir de protocol/fixtures. No editar.")
            appendLine("package com.magicfriends.client.fixtures")
            appendLine()
            appendLine("object Fixtures {")
            appendLine("    val messages: String = ${literal(root.resolve("messages.json").readText(Charsets.UTF_8))}")
            appendLine("    val scenarios: Map<String, String> = mapOf(")
            for (f in scenarios) appendLine("        ${literal(f.name)} to ${literal(f.readText(Charsets.UTF_8))},")
            appendLine("    )")
            appendLine("}")
        }, Charsets.UTF_8)
    }
}
kotlin.sourceSets.getByName("commonTest").kotlin.srcDir(generateFixtures.map { generatedFixturesDir.get().asFile })
