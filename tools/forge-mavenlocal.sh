# Publication locale des modules Forge (api, json, client, setup) vers ~/.m2 :
# consommation « bibliothèque versionnée » en attendant la publication Maven
# (ADR-001, demande F-01 ouverte).
#
# Ajusté le 2026-11-20 : les build.gradle de Forge v0.2.6 n'ont PAS maven-publish
# (constat : aucun plugin publication dans */build.gradle). Pour ne pas modifier le
# checkout épinglé (jamais touché), le plugin est injecté par initialisation Gradle (-I)
# et la publication de chaque module est lancée depuis la racine du build forge.
#
# Usage : tools/forge-mavenlocal.sh <chemin-forge> <version> [gradle-bin]

set -euo pipefail
FORGE="${1:?usage : forge-mavenlocal.sh <chemin-forge> <version> [gradle-bin]}"
VERSION="${2:?version (ex. 0.2.6)}"
GRADLE="${3:-gradle}"

# Script d'initialisation : ajoute maven-publish aux 4 modules bibliothèque
# (api, json, client, setup — jamais app ni engine, hors périmètre coquille).
INIT="$(mktemp -d)/forge-publish-init.gradle"
cat > "$INIT" <<'GRADLE'
// Injection de maven-publish aux modules bibliothèque de Forge (ADR-001).
def modulesBibliotheque = ['api', 'json', 'client', 'setup']
if (modulesBibliotheque.contains(project.name)) {
    apply plugin: 'maven-publish'
    publishing {
        publications {
            maven(MavenPublication) {
                from components.java
            }
        }
    }
}
GRADLE

cd "$FORGE"
"$GRADLE" --no-daemon -q -I "$INIT" \
    :api:publishToMavenLocal :json:publishToMavenLocal \
    :client:publishToMavenLocal :setup:publishToMavenLocal

echo "publiés : jo.forge:{api,json,client,setup}:$VERSION (mavenLocal)"
