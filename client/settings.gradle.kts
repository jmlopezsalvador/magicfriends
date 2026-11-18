pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
// Las apps la consumen con includeBuild("../magicfriends/client") y dependen de «com.magicfriends:magicfriends-client».
rootProject.name = "magicfriends-client"
