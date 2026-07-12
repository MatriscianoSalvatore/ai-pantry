pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "AI Pantry"
include(":app")
// AI pack (Play for On-device AI): Gemma 3n E2B splittato in chunk ≤1.5GB
include(":gemma3n_e2b_part0")
include(":gemma3n_e2b_part1")
include(":gemma3n_e2b_part2")
