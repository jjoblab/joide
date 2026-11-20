#!/usr/bin/env bash
# Publication locale des artefacts celeris vers ~/.m2 en attendant un tag JitPack
# (demande B-01) : même mécanisme que forge-mavenlocal.sh (ADR-001).
# Le checkout épinglé n'est jamais modifié : les conventions celeris portent déjà
# maven-publish ; la publication des artefacts JVM purs suffit (la coquille Android
# consomme les AAR via le build composite ? Non : les AAR exigent le SDK Android).
# Les modules AAR sont donc construits par la CI (SDK présent chez l'exécutant).
# Usage : tools/celeris-mavenlocal.sh <chemin-celeris> [gradle-bin]

set -euo pipefail
CELERIS="${1:?usage : celeris-mavenlocal.sh <chemin-celeris> [gradle-bin]}"
GRADLE="${2:-gradle}"

cd "$CELERIS"
# Tous les artefacts de la brique (l'agrégateur celeris inclut vue + widgets +
# couche hôte + grammaires) : la publication des conventions exporte déjà les
# modules ; on publie tout ce qui est publiabe JVM/AAR.
"$GRADLE" --no-daemon -q publishToMavenLocal

echo "publiés : jo.celeris:* (mavenLocal)"
