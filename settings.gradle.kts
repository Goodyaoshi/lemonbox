pluginManagement {
    repositories {
        // 仓库策略：本地开发优先阿里云镜像加速；CI（GitHub Actions）只走官方源。
        // 原因：在 GitHub 美国节点上，阿里云镜像无法解析部分插件（如 KSP 的 plugin marker），
        // 会导致 “Plugin ... was not found in any of the following sources” 而构建失败，
        // 故 CI 内完全不使用镜像，仅保留官方源。
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：本地走镜像，CI 只走官方源。
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "lemonbox"
include(":app")
