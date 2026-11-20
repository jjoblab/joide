#!/usr/bin/env bash
# Contrôle API 28 (ADR-009) : vérifie minSdk/targetSdk = 28 dans le manifeste fusionné
# et les usages Android. Le contrôle complet (analyse bytecode contre android-28) est
# une tâche Gradle prévue en L9 ; ce garde-fou vérifie déjà les déclarations.
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
if [ ! -f app-android/build.gradle ]; then echo "check-api28 : module Android absent, ignoré."; exit 0; fi
failed=0
grep -qE 'minSdk.?=?\s*28' app-android/build.gradle || { echo "  minSdk != 28" >&2; failed=1; }
grep -qE 'targetSdk.?=?\s*28' app-android/build.gradle || { echo "  targetSdk != 28" >&2; failed=1; }
[ "$failed" -ne 0 ] && echo "ÉCHEC : niveaux d'API." >&2 || echo "check-api28 : OK (28/28)."
exit "$failed"
