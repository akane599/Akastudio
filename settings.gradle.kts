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
rootProject.name = "AkaStudio"
include(":app")
include(":core:ingestion")
include(":core:color-engine")
include(":core:vision-curator")
include(":core:collage-composer")
include(":core:export-pipeline")
include(":feature:studio")
