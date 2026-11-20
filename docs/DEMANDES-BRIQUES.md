# DEMANDES-BRIQUES — registre des demandes aux briques de l'auteur

> Même règle que `DEMANDES-FORGE.md` : tout besoin manquant chez une brique
> (céleris, code-lsp, LayoutLib, codeide-terminal…) devient une **demande
> formelle ici**, jamais un contournement silencieux (conception §6). Une brique
> n'est jamais forkée ni modifiée en silence.

| Id | Date | Lot | Demande | Contexte et conséquence pour JoIDE | État |
|---|---|---|---|---|---|
| B-01 | 2026-11-20 | L5 | **Pousser une étiquette de version sur celeris** (p. ex. `v2.1.0`) pour activer la consommation JitPack | Le dépôt `jjoblab/celeris` n'a AUCUNE étiquette git (constat : `git tag` vide) alors que `jitpack.yml` existe et que le README renvoie vers une page Releases inexistante. JoIDE consomme la brique par mavenLocal du checkout cloné (ADR-001 bis, même mécanisme que Forge) : fonctionnel mais non reproductible par un tiers sans cloner. Une étiquette suffit à activer la voie JitPack documentée. | **ouvert** (contourné par mavenLocal) |
| B-02 | 2026-11-20 | L5/L8 | **Accès au registre GitHub Packages de code-lsp** (ou point d'entrée JitPack) + confirmation que 1.15.0 y est publié | Le moteur `jo.codeide.lsp.java:1.15.0` (tag v1.15.0) se publie vers GitHub Packages authentifié (jeton) ; le README racine cite encore 1.12.2 comme dernière publiée. JoIDE veut câbler les diagnostics LSP du moteur Java dans la section Problèmes ; blocage : pas de consommation anonyme. Point d'attention : lsp4j 0.21.2 côté code-lsp contre 1.0.0 côté `celeris:lsp-protocole` — JoIDE câblera son propre client lsp4j directement, aucun des deux n'impose l'autre. Le compagnon `ecj-art` (~3,2 Mo) est à budgéter dans l'APK. | **ouvert** |
| B-03 | 2026-11-20 | L5 | **Accord écrit de licence pour LayoutLib** (rendu XML) | Le dépôt `jjoblab/LayoutLib` est prêt sur JitPack (tag v3.7.1, `jitpack.yml` écrit pour JoIDE le 2026-11-18) mais porte la mention « Projet interne — aucun droit accordé sans autorisation explicite ». JoIDE n'embarque pas l'aperçu XML avant accord écrit (les deux autres briques sont Apache-2.0). | **ouvert** |

Ajouts à venir (lots suivants) : demandes au terminal (fork Termux) et à
`jjoblab/codeide` au lot L6 (consigne du propriétaire : consulter aussi
`jjoblab/codeide` en complément de `codeide-packages` à l'étape terminal).
