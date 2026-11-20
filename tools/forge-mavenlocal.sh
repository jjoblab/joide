# Publication locale des modules Forge (api, json, client, setup, engine) vers ~/.m2 :
# consommation « bibliothèque versionnée » en attendant la publication Maven
# (ADR-001, demande F-01 ouverte).
#
# Ajusté le 2026-11-20 : les build.gradle de Forge v0.2.6 n'ont PAS maven-publish
# (constat : aucun plugin publication dans */build.gradle). Pour ne pas modifier le
# checkout épinglé (jamais touché), le plugin est injecté par initialisation Gradle
# (-I). Un script d'initialisation s'évalue au niveau BUILD : l'accès à project
# passe par allprojects/afterEvaluate (le composant 'java' n'existe qu'après
# évaluation du projet).
#
# Usage : tools/forge-mavenlocal.sh <chemin-forge> <version> [gradle-bin]

set -euo pipefail
FORGE="${1:?usage : forge-mavenlocal.sh <chemin-forge> <version> [gradle-bin]}"
VERSION="${2:?version (ex. 0.2.6)}"
GRADLE="${3:-gradle}"

# Script d'initialisation : ajoute maven-publish aux modules bibliothèque de Forge
# (api, json, client, setup — coquille ; engine en plus : le moteur factice des
# tests d'intégration de JoIDE, jamais app).
INIT="$(mktemp -d)/forge-publish-init.gradle"
cat > "$INIT" <<'GRADLE'
// Injection de maven-publish aux modules bibliothèque de Forge (ADR-001).
allprojects {
    if (['api', 'json', 'client', 'setup', 'engine'].contains(project.name)) {
        apply plugin: 'maven-publish'
        afterEvaluate {
            publishing {
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
        }
    }
}
GRADLE

cd "$FORGE"
"$GRADLE" --no-daemon -q -I "$INIT" \
    :api:publishToMavenLocal :json:publishToMavenLocal \
    :client:publishToMavenLocal :setup:publishToMavenLocal \
    :engine:publishToMavenLocal

echo "publiés : jo.forge:{api,json,client,setup,engine}:$VERSION (mavenLocal)"
