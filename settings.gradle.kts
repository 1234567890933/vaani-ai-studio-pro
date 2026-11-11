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
        // ONNX Runtime ships in this package so the build works offline.
        flatDir { dirs("vendor/onnxruntime") }
    }
}

rootProject.name = "HindiDubber"
include(":app")
