// JoIDE — réglages du build multi-modules.
// Modules JVM purs : toujours inclus (constructibles et testables sans SDK Android).
// Modules Android (app-android) : inclus seulement si un SDK Android est détecté
// (ANDROID_HOME) ; refus explicite par JOIDE_SKIP_ANDROID=1 — le cœur JVM se
// construit et se teste toujours.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provision automatique des JDK de chaîne (le bac à sable n'a qu'un JRE 21).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Forge (ADR-001) : publication locale d'un checkout épinglé vers mavenLocal
        // (tools/forge-mavenlocal.sh), tant que Forge ne publie pas sur un dépôt Maven
        // (demande F-01).
        mavenLocal()
        google()
        mavenCentral()
    }
}

val refusAndroid = providers.gradleProperty("joide.skipAndroid").getOrElse("") == "1"
        || providers.environmentVariable("JOIDE_SKIP_ANDROID").getOrElse("") == "1"
val sdkDetecte = providers.environmentVariable("ANDROID_HOME").getOrElse("").isNotEmpty()
        && providers.environmentVariable("ANDROID_SDK_ROOT").getOrElse("").isNotEmpty()

rootProject.name = "joide"

include(":core-model")
include(":core-store")
include(":workbench-spi")
include(":build-forge")
include(":build-gradle-core")
if (sdkDetecte && !refusAndroid) {
    include(":app-android")
} else {
    logger.lifecycle("Module Android ignoré (ANDROID_HOME absent ou refus explicite) : build JVM pur.")
}
