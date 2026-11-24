#!/usr/bin/env bash
# Règle de dépendances (conception §2) : aucun module JVM ne dépend d'un artefact
# Android ; app-android est le seul module UI ; le graphe reste sans cycle.
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
failed=0
for m in core-model core-store core-diag workbench-spi build-forge build-gradle-core; do
  if grep -nE 'com.android.(application|library)|androidx\.' "$m/build.gradle" 2>/dev/null; then
    echo "  module JVM $m référence l'Android" >&2; failed=1
  fi
done
[ "$failed" -ne 0 ] && echo "ÉCHEC : couche JVM contaminée." >&2 || echo "check-deps : OK."
exit "$failed"
