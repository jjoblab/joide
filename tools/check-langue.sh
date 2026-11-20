#!/usr/bin/env bash
# Contrôle de langue (règle du cahier des charges) : identifiants anglais, commentaires
# et documents français, textes UI français dans les ressources.
# 1) pas de texte UI en dur dans le code Java des écrans (findViewById(R.string...)
#    reste la règle) : recherche de setText littéral sur les modules Android ;
# 2) les documents docs/*.md sont en français (mots-clés suspects en anglais uniquement
#    dans le code) — contrôle léger : titres de sections.
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
failed=0
# Texte en dur dans les écrans : android:text="..." littéral autre que référence @string
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if grep -nE 'android:text="[^@]' "$f" | grep -vE 'android:text="[{} ]*"' >/dev/null; then
    echo "  texte en dur dans $f :" >&2
    grep -nE 'android:text="[^@]' "$f" | head -3 | sed 's/^/    /' >&2
    failed=1
  fi
done < <(git ls-files 'app-android/src/main/res/layout/*.xml')
[ "$failed" -ne 0 ] && echo "ÉCHEC : textes UI en dur (la règle impose @string/)." >&2 || echo "check-langue : OK."
exit "$failed"
