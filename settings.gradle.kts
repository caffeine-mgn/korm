pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven(url = "http://nexus.xx/repository/maven-public/") {
            isAllowInsecureProtocol = true
        }
    }
}

dependencyResolutionManagement {
    repositories {
        // mavenLocal первым: kdatabase публикуется локально до выхода в Central,
        // и соседние репозитории должны видеть свежую версию сразу.
        mavenLocal()
        maven(url = "http://nexus.xx/repository/maven-public/") {
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "korm"
