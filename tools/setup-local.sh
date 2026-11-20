#!/usr/bin/env bash
# Mise en place locale : hooks git + fichier NON versionné des termes interdits.
# Les termes interdits ne sont volontairement PAS écrits ici.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath tools/hooks
echo "Hooks installés (core.hooksPath = tools/hooks) : pre-commit, commit-msg."
TERMS_FILE="$(git rev-parse --git-dir)/info/forbidden-terms.txt"
if [ ! -f "$TERMS_FILE" ]; then
  touch "$TERMS_FILE"
  echo "Fichier créé : $TERMS_FILE — À PEUPLER MANUELLEMENT (voir docs/REFERENCES.md pour les termes concernés)."
else
  echo "Fichier déjà présent : $TERMS_FILE"
fi
echo "Vérification : ./tools/check-terms.sh"
