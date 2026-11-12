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
        // Injekt, which Mihon and Aniyomi extensions use, is only published there.
        maven("https://jitpack.io") { content { includeGroup("com.github.mihonapp") } }
    }
}

rootProject.name = "aniliphy"
include(":app", ":source-api", ":tachi-compat")
