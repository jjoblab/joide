#!/usr/bin/env bash
# Lot 7 : chaque réglage IA a un écrivain ET un lecteur (matrice docs/analyse-ia.md).
# Les clés « à venir » sont explicites ici ET visibles dans l'écran.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="app-android/src/main/java"
# clé:statut  (statut = cable | a-venir | interne)
KEYS=(
  "fournisseur:cable"
  "modele_:cable"
  "base_url_custom:cable"
  "effort:cable"
  "tokens_max:cable"
  "iterations_max:cable"
  "mode_permission:cable"
  "or_attribution:cable"
  "or_sort:cable"
  "or_allow_fallbacks:cable"
  "or_require_parameters:cable"
  "or_data_collection:cable"
  "recherche_web:a-venir"
  "serveur_mcp:a-venir"
  "laboratoire_averti:interne"
)
fail=0
for entry in "${KEYS[@]}"; do
  key="${entry%%:*}"; status="${entry##*:}"
  writers=$(grep -rE "put(String|Int|Boolean)\(\"${key}" "$SRC" --include="*.java" | wc -l)
  readers=$(grep -rE "get(String|Int|Boolean)\(\"${key}" "$SRC" --include="*.java" | wc -l)
  if [ "$writers" -eq 0 ]; then
    echo "check-cablage-ia : AUCUN ÉCRIVAIN pour « ${key} »"; fail=1
  fi
  if [ "$readers" -eq 0 ] && [ "$status" != "a-venir" ]; then
    echo "check-cablage-ia : AUCUN LECTEUR pour « ${key} » (statut ${status})"; fail=1
  fi
  if [ "$readers" -eq 0 ] && [ "$status" = "a-venir" ]; then
    echo "check-cablage-ia : « ${key} » à venir (annoncé dans l écran et la matrice)"
  fi
done
if [ "$fail" -ne 0 ]; then exit 1; fi
echo "check-cablage-ia : OK (clés vérifiées, matrice docs/analyse-ia.md)."