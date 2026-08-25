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

rootProject.name = "samaroh-android"

include(":app")
include(":core:designsystem")
include(":core:i18n")
include(":core:model")
include(":core:database")
include(":core:data")
include(":core:sync")
include(":core:auth")
include(":core:google")
include(":core:invoice")
include(":core:testing")
include(":feature:booking")
include(":feature:expenses")
include(":feature:inventory")
include(":feature:menu")
include(":feature:onboarding")
include(":feature:reports")
