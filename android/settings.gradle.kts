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
        // 카카오 지도 Android SDK v2 (장소 탭 지도)
        maven("https://devrepo.kakao.com/nexus/repository/kakaomap-releases/")
    }
}
rootProject.name = "assistant"
include(":app")
