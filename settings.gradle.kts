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
// AI pack (Play for On-device AI): il modello LLM splittato in chunk ≤1.5GB
include(":llm_pack_0")
include(":llm_pack_1")
include(":llm_pack_2")
