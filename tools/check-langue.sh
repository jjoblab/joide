#!/usr/bin/env bash
# Contrôle de langue (règle du cahier des charges, consigne du propriétaire 2026-11-20) :
# 1) identifiants du code source en ANGLAIS (classes, méthodes, variables, paquets,
#    ids/layouts XML) — commentaires, javadoc et documents restent en français ;
# 2) pas de texte UI en dur dans les layouts (uniquement des références @string/).
# Heuristique identifiants : un identifiant Java contenant des mots français courants
# (accents exclus déjà par javac) est signalé ; liste blanche de termes métier français
# acceptés dans les commentaires (le scan se limite aux déclarations).
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
failed=0

# Mots français interdits dans les IDENTIFIANTS (déclarations de classes/methodes/variables/ids)
MOTS="(Accueil|Atelier|Activite|FragmentAccueil|FragmentAtelier|Assistant|Choix|Preferences|Detecteur|Evenement|Capacites|Etat|Installation|Gestionnaire|Analyseur|Sortie|Travail|Methode|Fonction|Donnees|Fichier|Dossier|Erreur|Avancee|Message|Numero|Conteneur|Feuille|Onglets|Barre|Bouton|Carte|Zone|Etat|Nouveau|Importer|Enregistrer|Construire|Terminal|Journal)"
# termes structurels anglais attendus dans les ids/layouts
while IFS= read -r f; do
  [ -f "$f" ] || continue
  # 1) déclarations Java : class/interface/enum/record + méthodes
  if grep -nP "^\s*(public|private|protected|static|final|abstract|sealed)*.*\b(class|interface|enum|record)\s+[A-Z]$MOTS" "$f" >/dev/null 2>&1; then
    echo "  identifiant français (type) dans $f :" >&2
    grep -nP "^\s*(public|private|protected|static|final|abstract|sealed)*.*\b(class|interface|enum|record)\s+[A-Z]$MOTS" "$f" | head -3 | sed 's/^/    /' >&2
    failed=1
  fi
  # 2) ids de layout et noms de fichiers de layout
  if echo "$f" | grep -qE '(fragment_|activite|atelier|accueil|assistant)' ; then
    echo "  nom de fichier de layout français : $f" >&2; failed=1
  fi
done < <(git ls-files '*.java' 'app-android/src/main/res/layout/*.xml' 2>/dev/null)

# layouts : ids français dans les XML
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if grep -nP '@\+id/[a-z_]*([a-z])-?(accueil|atelier|bouton|barre|carte|feuille|onglet|zone|conteneur|etat|nouveau|importer)' "$f" >/dev/null 2>&1; then
    echo "  id français dans $f :" >&2
    grep -nP '@\+id/[a-z_]*(accueil|atelier|bouton|barre|carte|feuille|onglet|zone|conteneur|etat)' "$f" | head -3 | sed 's/^/    /' >&2
    failed=1
  fi
  # texte en dur (règle : @string/ uniquement)
  if grep -nE 'android:text="[^@]' "$f" | grep -vE 'android:text="[{} ]*"' >/dev/null 2>&1; then
    echo "  texte en dur dans $f :" >&2
    grep -nE 'android:text="[^@]' "$f" | head -3 | sed 's/^/    /' >&2
    failed=1
  fi
done < <(git ls-files 'app-android/src/main/res/layout/*.xml' 2>/dev/null)

if [ "$failed" -ne 0 ]; then
  echo "ÉCHEC : identifiants français ou textes en dur (règle : identifiants anglais, UI en @string/)." >&2
else
  echo "check-langue : OK (identifiants anglais, textes UI en ressources)."
fi
exit "$failed"
