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
    // AGP pour le module Android (version épinglée, ADR conception §1).
    plugins {
        id("com.android.application") version "8.7.3"
    }
}

plugins {
    // Provision automatique des JDK de chaîne (le bac à sable n'a qu'un JRE 21).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Forge (ADR-001) : aucun paquet Maven jo.forge n'est publié (ni GitHub Packages,
        // ni JitPack) ; Forge ne publie que des releases GitHub (zips + manifest.json).
        // Les bibliothèques jo.forge:*:0.2.13 viennent donc d'un mavenLocal alimenté par
        // tools/forge-mavenlocal.sh <chemin-forge> 0.2.13.
        mavenLocal()
        google()
        mavenCentral()
        // Termux : uniquement les modules Apache-2.0 (terminal-emulator, terminal-view)
        // via JitPack — filtre strict, rien d'autre ne vient de ce dépôt.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.termux.termux-app")
                // celeris : JitPack publie en com.github.jjoblab.celeris:<module>:v<tag>
                includeGroupByRegex("com\\.github\\.jjoblab(\\..*)?")
            }
        }
    }
}

val refusAndroid = providers.gradleProperty("joide.skipAndroid").getOrElse("") == "1"
        || providers.environmentVariable("JOIDE_SKIP_ANDROID").getOrElse("") == "1"
val sdkDetecte = providers.environmentVariable("ANDROID_HOME").getOrElse("").isNotEmpty()

rootProject.name = "joide"

include(":core-model")
include(":core-store")
include(":terminal-core")
include(":ai")
include(":workbench-spi")
include(":build-forge")
include(":build-gradle-core")
include(":core-git")
include(":core-files")
include(":core-search")
if (sdkDetecte && !refusAndroid) {
    include(":app-android")
} else {
    logger.lifecycle("Module Android ignoré (ANDROID_HOME absent ou refus explicite) : build JVM pur.")
}
