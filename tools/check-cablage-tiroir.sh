#!/usr/bin/env bash
# Lot 8 : matrice de câblage du tiroir gauche (docs/analyse-tiroir.md).
# 1) chaque préférence de l'explorateur est définie et utilisée au moins
#    deux fois (écrivain et lecteur — les clés vivent en constantes) ;
# 2) chaque ancrage `Fichier#symbole` de la matrice existe dans le code.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="app-android/src/main/java"
MATRICE="docs/analyse-tiroir.md"
# Les ancrages vivent dans toute la maison : app-android ET modules JVM
# (core-files, core-git, core-search, core-outline, workbench-spi…).
fail=0

# clé:constante
PREFS=(
  "explorateur_fichiers_caches:KEY_HIDDEN"
  "explorateur_packages_compacts:KEY_COMPACT"
  "explorateur_badges:KEY_BADGES"
  "explorateur_suivi_actif:KEY_FOLLOW"
)
for entry in "${PREFS[@]}"; do
  key="${entry%%:*}"
  constant="${entry##*:}"
  defined=$(grep -rE "String ${constant} = \"${key}\"" "$SRC" --include="*.java" | wc -l || true)
  if [ "$defined" -eq 0 ]; then
    echo "check-cablage-tiroir : constante « ${constant} » (${key}) non définie"; fail=1
    continue
  fi
  usages=$(grep -rE "\b${constant}\b" "$SRC" --include="*.java" | grep -v "String ${constant}" | wc -l || true)
  if [ "$usages" -lt 2 ]; then
    echo "check-cablage-tiroir : « ${key} » utilisée ${usages} fois (écrivain ET lecteur attendus)"; fail=1
  fi
done

# Chaque `Fichier#symbole` (backticks) de la matrice doit exister : le
# fichier sous SRC, le symbole dans son texte.
for anchor in $(grep -oE '`[A-Za-z]+#[A-Za-z0-9_]+`' "$MATRICE" | tr -d '`' | sort -u); do
  file="${anchor%%#*}"
  symbol="${anchor##*#}"
  path=$(find . -name "${file}.java" -not -path "*/.git/*" -not -path "*/build/*" | head -1 || true)
  if [ -z "$path" ]; then
    echo "check-cablage-tiroir : fichier introuvable pour « ${anchor} »"; fail=1
    continue
  fi
  # Méthode, type, constante ou champ : le symbole doit être mentionné.
  if ! grep -qw "${symbol}" "$path"; then
    echo "check-cablage-tiroir : symbole « ${symbol} » absent de ${file}.java"; fail=1
  fi
done

if [ "$fail" -ne 0 ]; then exit 1; fi
echo "check-cablage-tiroir : OK (préférences et ancrages vérifiés, matrice ${MATRICE})."
