pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AegisAssistant"
include(":apps:androidApp")
include(":apps:desktopApp")
include(":apps:macosApp")

include(":shared:core")
include(":shared:database")
include(":shared:platform-api")
include(":shared:model-api")
include(":shared:sync")
include(":shared:desktop-sync")

include(":platform:android")
include(":platform:windows")
