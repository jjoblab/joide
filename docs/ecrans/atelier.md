# Fiche écran — Atelier (téléphone)

**Parent :** accueil / création de projet. **Suivants :** paramètres, terminal (mode Gradle), IA.

- Objectif : éditer et construire. Topologie : barre 52 dp (retour, tiroir fichiers, titre, enregistrer, construire, ⋯), zone éditeur (briques de l'auteur), tiroir IA à droite, **bottom sheet à en-tête unique** et quatre sections : Console (build), Problèmes (LSP + diagnostics), Journal (IDE), Logcat (application de l'utilisateur) — aucune répétition d'en-tête (règle §3 du cahier des charges).
- Événements : Console et Problèmes consomment le `BuildBackend` du mode actif (conception §4).
- Interactions : bottom sheet à deux détentes (56 dp / plein) ; erreurs de console cliquables → saut éditeur ; strip d'avis unique sous la barre (compatibilité de mode, limites Forge).
- Adaptatif : ≥720 dp : tiroirs persistants, sheet devient panneau.
- REF : REF-006, REF-007, REF-018.
