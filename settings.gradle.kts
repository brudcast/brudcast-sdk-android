pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "brudcast-sdk-android"

include(":sdk-core", ":sdk-inapp", ":sdk-push", ":sdk-push-firebase", ":sdk-bom", ":sample")
