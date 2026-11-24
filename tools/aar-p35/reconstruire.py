#!/usr/bin/env python3
"""Reconstruit le dépôt Maven local `tools/aar-p35/repo` — ADR-020.

Contexte (ADR-020) : le retour à `compileSdk 35` se heurte aux exigences de
métadonnées des AAR consommés — céleris v2.2.18 (android-view, android-host,
android-widgets : `minCompileSdk 36`) et lsp-host 1.15.0 (`minCompileSdk 37`,
`minCompileMinorSdk 2`). Aucune version plus ancienne compatible n'existe
(céleris exige 36 depuis 2.1.0 ; code-lsp n'a publié que 1.15.0).

Le contrôle d'AGP qui bloque (`Dependency's minCompileSdk (X) is greater
than this module's compileSdk version (Y)`) ne lit QUE le fichier
`META-INF/com/android/build/gradle/aar-metadata.properties` de l'AAR : le
bytecode, les ressources et le POM sont inchangés. Ce script reconstruit donc
des copies locales IDENTIQUES à l'exception de cette seule ligne de
métadonnées (minCompileSdk=35, suppression de minCompileMinorSdk) — le dépôt
`tools/aar-p35/repo` est servi AVANT JitPack/GitHub Packages (résolution par
ordre de déclaration), avec un filtre de contenu strict sur les deux groupes.

Rejouable à tout moment (téléchargements amont + patch) :

    JOIDE_GH_PACKAGES_TOKEN=ghp_xxx python3 tools/aar-p35/reconstruire.py

Ne modifie jamais le dépôt git : les artefacts produits sont commités une
fois, le script ne sert qu'à prouver et refaire la provenance.
"""

import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile

ICI = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(ICI, "repo")

# Artefacts à servir localement : (groupe maven, artefact, version, dépôt amont).
# JitPack publie les groupes com.github.jjoblab.celeris:* ; code-lsp publie
# jo.codeide.lsp.host sur GitHub Packages (jeton requis, même pour lire).
JETON = os.environ.get("JOIDE_GH_PACKAGES_TOKEN", "")

ARTEFACTS = [
    ("com.github.jjoblab.celeris", "android-view", "v2.2.18",
     "https://jitpack.io/com/github/jjoblab/celeris/android-view/v2.2.18"),
    ("com.github.jjoblab.celeris", "android-host", "v2.2.18",
     "https://jitpack.io/com/github/jjoblab/celeris/android-host/v2.2.18"),
    ("com.github.jjoblab.celeris", "android-widgets", "v2.2.18",
     "https://jitpack.io/com/github/jjoblab/celeris/android-widgets/v2.2.18"),
    ("jo.codeide.lsp.host", "jo-codeide-lsp-host", "1.15.0",
     "https://maven.pkg.github.com/jjoblab/code-lsp/jo/codeide/lsp/host/"
     "jo-codeide-lsp-host/1.15.0"),
]

META = "META-INF/com/android/build/gradle/aar-metadata.properties"
CIBLE_MIN_COMPILE = "35"


def telecharger(url, sortie):
    requete = urllib.request.Request(url)
    if "maven.pkg.github.com" in url:
        if not JETON:
            sys.exit("JOIDE_GH_PACKAGES_TOKEN requis pour %s" % url)
        import base64
        cle = base64.b64encode(("jjoblab:" + JETON).encode()).decode()
        requete.add_header("Authorization", "Basic " + cle)
    with urllib.request.urlopen(requete) as reponse, open(sortie, "wb") as fichier:
        shutil.copyfileobj(reponse, fichier)


def patcher_aar(aar_amont, aar_cible):
    """Recopie l'AAR en réécrivant SEULEMENT aar-metadata.properties.

    Le classement des entrées est conservé ; le remplacement se fait sur un
    octet-stream équivalent (zipfile réécrit l'archive mais pas le contenu).
    """
    provisoire = aar_cible + ".provisoire"
    modifie = False
    with zipfile.ZipFile(aar_amont) as entree, \
            zipfile.ZipFile(provisoire, "w", zipfile.ZIP_DEFLATED) as sortie:
        for info in entree.infolist():
            contenu = entree.read(info.filename)
            if info.filename == META:
                texte = contenu.decode("utf-8")
                lignes = []
                for ligne in texte.splitlines():
                    cle = ligne.split("=", 1)[0]
                    if cle == "minCompileSdk":
                        lignes.append("minCompileSdk=%s" % CIBLE_MIN_COMPILE)
                        modifie = True
                    elif cle in ("minCompileMinorSdk", "minCompileSdkExtension"):
                        continue  # 35 n'a ni mineur ni extension exigés
                    else:
                        lignes.append(ligne)
                contenu = ("\n".join(lignes) + "\n").encode("utf-8")
            sortie.writestr(info, contenu)
    if not modifie:
        os.remove(provisoire)
        sys.exit("aar-metadata.properties introuvable dans %s" % aar_amont)
    os.replace(provisoire, aar_cible)


def main():
    if os.path.isdir(REPO):
        shutil.rmtree(REPO)
    for groupe, artefact, version, base in ARTEFACTS:
        dossier = os.path.join(REPO, *groupe.split("."), artefact, version)
        os.makedirs(dossier)
        nom = "%s-%s" % (artefact, version)
        aar_amont = tempfile.mktemp(suffix=".aar")
        telecharger(base + "/" + nom + ".aar", aar_amont)
        telecharger(base + "/" + nom + ".pom",
                    os.path.join(dossier, nom + ".pom"))
        patcher_aar(aar_amont, os.path.join(dossier, nom + ".aar"))
        os.remove(aar_amont)
        print("ok  %s:%s:%s" % (groupe, artefact, version))
    print("Dépôt local reconstruit : %s" % REPO)


if __name__ == "__main__":
    main()
