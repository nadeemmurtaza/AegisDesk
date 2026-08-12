pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "NewaxAssistant"

include(":shared:core")
include(":shared:database")
include(":shared:platform-api")
include(":shared:model-api")
include(":shared:sync")
include(":shared:desktop-sync")

include(":apps:android")
include(":apps:desktop")
include(":apps:macos")
include(":apps:ios")

include(":platform-impl:android")
include(":platform-impl:windows")
include(":platform-impl:macos")
include(":platform-impl:ios")