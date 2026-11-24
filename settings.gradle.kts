// JoIDE — réglages du build multi-modules.
// Modules JVM purs : toujours inclus (constructibles et testables sans SDK Android).
// Modules Android (app-android) : inclus seulement si un SDK Android est détecté
// (ANDROID_HOME) ; refus explicite par JOIDE_SKIP_ANDROID=1 — le cœur JVM se
// construit et se teste toujours.

pluginManagement {
    repositories {
        // Miroir optionnel (bac à sable limité en débit par Maven Central) :
        // JOIDE_MIRROR=https://maven.aliyun.com/repository/public l’insère en tête.
        val miroir = providers.environmentVariable("JOIDE_MIRROR").getOrElse("")
        if (miroir.isNotEmpty()) {
            maven { url = uri(miroir) }
        }
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
    // Provision automatique des JDK de chaîne (utile quand la machine n'a
    // qu'un JRE). Résolution réseau au démarrage : contournable par
    // JOIDE_NO_FOOJAY=1 quand un JDK local suffit (bac à sable hors-ligne,
    // dépôt Maven limité en débit) — le toolchain JAVA_HOME prend le relais.
    if (providers.environmentVariable("JOIDE_NO_FOOJAY").getOrElse("") != "1") {
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Miroir optionnel (même règle que pluginManagement ci-dessus).
        val miroir = providers.environmentVariable("JOIDE_MIRROR").getOrElse("")
        if (miroir.isNotEmpty()) {
            maven { url = uri(miroir) }
        }
        // AAR locaux compileSdk 35 (ADR-020) : céleris v2.2.18 (android-view,
        // android-host, android-widgets) et lsp-host 1.15.0 exigent 36/37.2
        // dans leurs métadonnées — aucune version amont compatible n'existe.
        // Servis en COPIES LOCALES (tools/aar-p35/repo, reconstruites par
        // tools/aar-p35/reconstruire.py) dont SEULE la ligne minCompileSdk des
        // métadonnées est ajustée : bytecode, ressources et POM identiques à
        // l'amont. Ce dépôt se déclare AVANT JitPack/GitHub Packages (résolution
        // par ordre de déclaration) ; le filtre de contenu est strict — les
        // autres artefacts com.github.jjoblab.* et jo.codeide.lsp.* continuent
        // de venir des registres distants.
        maven {
            name = "aar-p35"
            url = uri(settingsDir.resolve("tools/aar-p35/repo").toURI())
            content {
                includeGroup("com.github.jjoblab.celeris")
                includeGroup("jo.codeide.lsp.host")
            }
        }
        // Forge (ADR-001) : aucun paquet Maven jo.forge n'est publié sur un dépôt
        // distant ; Forge publie ses releases GitHub (zips + manifest.json) et,
        // depuis v0.2.24, ses modules bibliothèque sur mavenLocal.
        // Les bibliothèques jo.forge:*:0.2.25 viennent donc d'un mavenLocal alimenté
        // par ./gradlew publishToMavenLocal dans le dépôt Forge.
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
                // codeide-tooling (protocole tooling 1.2.0, transitif de lsp-host)
                // est publié sur JitPack sans jeton — la voie anonyme (B-04).
                includeGroupByRegex("com\\.github\\.jjoblab\\.codeide-tooling(\\..*)?")
            }
        }
        // code-lsp (B-02 → LOT-11) : les packs LSP v1.15.0 (jo.codeide.lsp.*) se
        // publient sur le GitHub Packages de jjoblab/code-lsp — registre à jeton
        // même pour lire : JOIDE_GH_PACKAGES_TOKEN (localement le PAT du
        // propriétaire, en CI le GITHUB_TOKEN du dépôt avec packages: read).
        // Sans jeton, la résolution de code-lsp échoue mais le reste du build
        // reste résolvable (les groupes sont filtrés ci-dessous).
        maven {
            name = "code-lsp"
            url = uri("https://maven.pkg.github.com/jjoblab/code-lsp")
            val jetonLsp = providers.environmentVariable("JOIDE_GH_PACKAGES_TOKEN")
                .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                .getOrElse("")
            credentials {
                username = "jjoblab"
                password = jetonLsp
            }
            content {
                includeGroupByRegex("jo\\.codeide\\.lsp(\\..*)?")
            }
        }
        // Dépendances IntelliJ du pack Kotlin de code-lsp (syntax-api,
        // lang-syntax) : ces artefacts org.jetbrains ne sont PAS sur Maven
        // Central, ils vivent sur le dépôt public JetBrains — filtre strict
        // au groupe, rien d'autre ne vient de ce registre.
        maven {
            name = "jetbrains-intellij-dependencies"
            url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
            content {
                includeGroup("org.jetbrains")
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
include(":core-diag")
include(":core-index")
// LOT-11 : le client LSP pur JVM — traduction du classpath Forge vers le
// protocole tooling, conversion lsp4j→céleris, client LanguageClient.
include(":core-lsp")
include(":terminal-core")
include(":ai")
include(":workbench-spi")
include(":build-forge")
include(":build-gradle-core")
include(":core-git")
include(":core-files")
include(":core-search")
include(":core-outline")
if (sdkDetecte && !refusAndroid) {
    include(":app-android")
} else {
    logger.lifecycle("Module Android ignoré (ANDROID_HOME absent ou refus explicite) : build JVM pur.")
}
