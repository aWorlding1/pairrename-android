pluginManagement {
    repositories {
        maven {
            url = uri("https://dl.google.com/android/maven2")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl.google.com/android/maven2") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "PairRename"
include(":app")
