# Inventaire de la génération 1 — classement différé

> Phase 0 (inventaire). Conformément à la consigne reçue le 2026-11-20 (« inutile de
> consulter la génération 1 de JoIDE ; sache seulement que pour l'éditeur c'est céleris,
> LSP c'est code-lsp et pour l'aperçu XML c'est LayoutLib »), la génération 1
> (`jjoblab/joide-old`, renommée pour libérer le nom) n'a **pas** été consultée pendant
> les phases 0-3 : ni son code, ni ses ADR, ni ses écrans. Ce document ne contient donc
> **pas** le tableau de classement garder / adapter / remplacer / abandonner demandé par
> le cahier des charges : il consigne les éléments déjà connus sans consultation, la
> méthode de l'inventaire réel et son déclencheur.

## 1. Éléments connus sans consultation

| Élément | Source de connaissance | Décision déjà arrêtée (cahier des charges) |
|---|---|---|
| Éditeur + coloration syntaxique | consigne du propriétaire : **céleris** | Consommer la brique en version épinglée ; jamais recoder (L5) |
| Moteurs LSP | consigne du propriétaire : **code-lsp** | Consommer la brique en version épinglée ; diagnostics vers la section Problèmes (L5) |
| Aperçu de mises en page XML | consigne du propriété : **LayoutLib** | Consommer la brique en version épinglée ; intégration atelier (L5) |
| Moteur de build interne | cahier des charges §4.8 | **Remplacé** par le mode Forge ; conservation seulement avec ADR justifié face à Forge, sinon demande formelle (`DEMANDES-FORGE.md`) |
| Compilateur Kotlin forké (VM ouvrière) | cahier des charges §2 | Non reconduit (produit Java 17 ; le besoin LSP Kotlin appartient à la brique dédiée) |
| Module d'agent IA, écrans, ADR | cahier des charges §2 (existence) | Aucune reprise directe : l'architecture des deux modes est nouvelle ; les mentions interdites de la génération 1 ne traversent pas (contrôle `tools/check-terms.sh`) |
| Terminal | cahier des charges §2 (absence constatée) | Nouveau composant de premier ordre (L6) |
| Versions d'intégration des briques | cahier des charges §2 (« se lisent dans la génération 1 ») | À lire lors du câblage L5 (lecture des fichiers de build de la génération 1 alors autorisée, ciblée et notée) |

## 2. Méthode de l'inventaire réel (quand la consultation sera autorisée)

1. Cloner `jjoblab/joide-old` hors du dépôt de travail (lecture seule, jamais en
   sous-module).
2. Lister modules Gradle, écrans (`docs/ecrans/`), ADR, composants d'agent.
3. Pour chaque élément : classement **garder / adapter / remplacer / abandonner** avec
   justification courte, portée dans ce document (nouvelle version).
4. Nettoyage obligatoire de tout apport : les mentions interdites par le cahier des
   charges §5 (y compris les noms de fichiers d'ADR de la génération 1) ne traversent
   jamais.
5. Tout élément du moteur interne dont la conservation serait défendable passe par un
   ADR face à Forge ou par une demande formelle (`DEMANDES-FORGE.md`) — jamais les deux
   moteurs concurrents dans l'application (§4.8).

## 3. Décision du propriétaire à consigner

- La génération 1 est un **input d'expérience**, pas une base de code : le nouveau dépôt
  repart d'un squelette neuf (L0).
- L'inventaire détaillé par élément est **différé jusqu'à autorisation** ; son déclencheur
  naturel est le câblage des briques (L5), qui exigera de lire les versions d'intégration
  dans la génération 1 conformément au cahier des charges §2.
