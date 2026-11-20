# LOT-00 — Squelette (L0)

**Livré :** modules Gradle (core-model, core-store, workbench-spi, build-forge, build-gradle-core ;
app-android conditionnel au SDK), hooks git (pre-commit, commit-msg) + liste locale des termes
interdits, contrôles (termes, langue, dépendances, API 28), tests JVM verts (détection de projets,
préférences de mode atomiques, capacités honnêtes des deux backends, parseur de diagnostics
Gradle), CI GitHub Actions (contrôles + tests + APK sur étiquette), README, licence Apache-2.0.

**Comparaison à la référence (après implémentation) :** squelette sans équivalent observable
chez la référence (structure de dépôt) — pas d'entrée REF dédiée.

**Non vérifié (appareil réel) :** tout ce qui touche app-android (pas de SDK dans le bac à sable) ;
le module Android est inclus automatiquement quand ANDROID_HOME existe.

**Écarts :** consommation de Forge en attente de publication Maven (F-01) : le module build-forge
documente le mécanisme (ADR-001) ; les dépendances jo.forge seront posées à la publication ou via
composite/includeBuild lors du câblage L3.
