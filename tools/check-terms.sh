#!/usr/bin/env bash
# Contrôle des termes interdits sur l'ensemble du dépôt (mécanisme repris de Forge).
# Usage local  : ./tools/check-terms.sh
# Usage CI     : FORBIDDEN_TERMS=$'terme1\nterme2' ./tools/check-terms.sh
# Sources : 1) FORBIDDEN_TERMS ; 2) .git/info/forbidden-terms.txt ; sinon ignoré.
# Exception unique : docs/REFERENCES.md.
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
if [ -n "${FORBIDDEN_TERMS:-}" ]; then
  TERMS_SRC="variable FORBIDDEN_TERMS"; TERMS_RAW="$FORBIDDEN_TERMS"
elif [ -f .git/info/forbidden-terms.txt ]; then
  TERMS_SRC=".git/info/forbidden-terms.txt"; TERMS_RAW="$(cat .git/info/forbidden-terms.txt)"
else
  echo "check-terms : aucune liste de termes fournie. Contrôle ignoré."; exit 0
fi
mapfile -t TERMS < <(printf '%s\n' "$TERMS_RAW" | grep -vE '^\s*(#|$)' || true)
[ "${#TERMS[@]}" -eq 0 ] && { echo "check-terms : liste vide ($TERMS_SRC). Ignoré."; exit 0; }
echo "check-terms : ${#TERMS[@]} terme(s) interdit(s), source : $TERMS_SRC"
failed=0
while IFS= read -r file; do
  [ -z "$file" ] && continue
  case "$file" in docs/REFERENCES.md) continue ;; esac
  [ -f "$file" ] || continue
  for term in "${TERMS[@]}"; do
    if grep -Finq -- "$term" "$file"; then
      echo "  TERME INTERDIT « ${term} » dans ${file} :" >&2
      grep -Fin -- "$term" "$file" | head -3 | sed 's/^/      /' >&2
      failed=1
    fi
  done
done < <(git ls-files)
if git rev-parse HEAD >/dev/null 2>&1; then
  for term in "${TERMS[@]}"; do
    if git log --all --format='%s%n%b' | grep -Finq -- "$term"; then
      echo "  TERME INTERDIT « ${term} » dans l'historique des commits" >&2; failed=1
    fi
  done
fi
[ "$failed" -ne 0 ] && { echo "ÉCHEC : termes interdits présents (exception : docs/REFERENCES.md)." >&2; exit 1; }
echo "check-terms : OK, aucun terme interdit hors docs/REFERENCES.md."
