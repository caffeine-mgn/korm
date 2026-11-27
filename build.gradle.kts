import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "pw.binom.db"
/*
 * Порядок версии: -Pversion (так передаёт CI и так ждёт vanniktech) → тег
 * GitHub → libVersion из gradle.properties → 0.1.0.
 *
 * `-Pversion` обязан быть ПЕРВЫМ: Gradle уже использует это имя для
 * Project.version, и если читать libVersion раньше, свойство из
 * gradle.properties (0.1.0) перебьёт тег — релиз молча уедет версией 0.1.0.
 */
version = (findProperty("version") as String?)
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: System.getenv("GITHUB_REF_NAME")?.removePrefix("v")
    ?: (findProperty("libVersion") as String?)
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: "0.1.0"

kotlin {
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()
    mingwX64()

    // JS — под будущий IndexedDB. ORM не должна знать о платформе.
    js(IR) {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // api: типы Connection/ConnectionPool появляются в публичном API ORM,
            // потребитель должен видеть их без отдельного объявления.
            api(libs.kdatabase)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

mavenPublishing {
    /*
     * Публикация в Maven Central через vanniktech.
     *
     * ⚠️ Порядок релизов: korm зависит от kdatabase, поэтому сначала
     * публикуется kdatabase (pw.binom.db:kdatabase), и только потом korm —
     * иначе consumer не сможет резолвить зависимость из Central.
     */
    publishToMavenCentral(automaticRelease = true)

    /*
     * Два режима подписи:
     *  * CI: -PsigningUseGpg=true — ключ импортирован в keyring джобой;
     *  * локально: -PskipSigning=true — publishToMavenLocal без ключа.
     */
    if (findProperty("skipSigning") != "true") signAllPublications()

    coordinates(group.toString(), "korm", version.toString())

    pom {
        name.set("korm")
        description.set("ORM for Kotlin Multiplatform over the kdatabase connection pool")
        url.set("https://github.com/caffeine-mgn/korm")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("subochev")
                name.set("Anton Subochev")
                email.set("caffeine.mgn@gmail.com")
            }
        }
        scm {
            connection.set("https://github.com/caffeine-mgn/korm.git")
            url.set("https://github.com/caffeine-mgn/korm")
        }
    }
}

pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<org.gradle.plugins.signing.SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle("[signing] системный gpg (ключ из keyring)")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
