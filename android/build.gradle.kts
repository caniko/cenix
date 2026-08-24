plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("com.google.devtools.ksp") version "2.1.10-1.0.31" apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("checkPinnedDependencies") {
        doLast {
            configurations.forEach { configuration ->
                configuration.dependencies.withType<ExternalModuleDependency>().forEach { dependency ->
                    val version = dependency.version.orEmpty()
                    check(!dependency.isChanging && !version.contains('+') && !version.endsWith("SNAPSHOT") &&
                        !version.startsWith("latest.")) {
                        "Unpinned dependency: ${dependency.group}:${dependency.name}:$version"
                    }
                }
            }
        }
    }
}
