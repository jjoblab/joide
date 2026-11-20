#!/usr/bin/env bash
# Publication locale des modules Forge (api, json, client, setup) vers ~/.m2 :
# consommation « bibliothèque versionnée » en attendant la publication Maven
# (ADR-001, demande F-01). Usage : ./tools/forge-mavenlocal.sh <chemin-forge> <version>
set -euo pipefail
FORGE="${1:?usage : forge-mavenlocal.sh <chemin-forge> <version>}"
VERSION="${2:?version (ex. 0.2.6)}"
cd "$FORGE"
for m in api json client setup; do
  (cd "$m" && ../../gradle-*/bin/gradle --no-daemon -q publishToMavenLocal 2>/dev/null) \
    || (cd "$m" && gradle --no-daemon -q install)
  echo "publié : jo.forge:$m:$VERSION (mavenLocal)"
done
