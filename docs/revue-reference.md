# Revue critique — produit de référence et génération 1

> Phase 2. Revue critique du produit de référence (le produit n'est jamais nommé ici :
> voir `REFERENCES.md` pour l'épinglage et la licence) au prisme de six axes : ergonomie,
> performance perçue, accessibilité, sécurité de l'IA, robustesse, maintenabilité. La
> revue de la génération 1 suit : ce qui est bon, ce qui doit changer pour le nouveau
> découpage.

## Partie 1 — Revue du produit de référence

Gravités : **C** (critique, bloque la confiance ou la sécurité), **M** (majeur, dégrade
l'expérience), **m** (mineur, amélioration souhaitable), **i** (information, décision
d'adopter ou non).

| # | REF | Constat | Gravité | Ce que nous faisons |
|---|---|---|---|---|
| R-01 | REF-024 | Cibles tactiles des barres d'outils densifiées à 34 dp, sous les 48 dp recommandés — le compromis est documenté chez eux mais pénalise la motricité fine sur la cible « développeur au téléphone » que nous visons | M | Cible 48 dp minimale partout ; densité obtenue par la hiérarchie et le regroupement, pas par des cibles rétrécies ; 44 dp au pire avec zone tactile invisible étendue |
| R-02 | REF-025 | Reduce-motion géré « aux points d'appel » : couverture inégale par construction | m | Porte reduce-motion **dans le token de mouvement** (design-system §5) |
| R-03 | REF-011 | Fonction de retour maison en cascade (≈30 branches pour 36 destinations) : la navigation devient difficile à raisonner et à tester | M | **Table de navigation parent/enfant déclarative** fixée dès les fiches d'écrans (`docs/ecrans/`) ; le retour est une donnée, pas une cascade |
| R-04 | REF-003 | Segments d'accueil visibles mais non reliés au moteur (compte zéro inerte) : un contrôle affiché qui ne fait rien mine la confiance | M | Règle : une puce affichée a un comportement ; tout état non câblé est masqué |
| R-05 | REF-123 | **Clés API stockées en clair** — la référence le documente elle-même comme une dette (seam prévu, non livré) | **C** | Clés chiffrées **dès le premier jour** (cahier des charges §4.6) : clé de bibliothèque `security-crypto`, fallback Robolectric | Amélioré |
| R-06 | REF-123 | La conversation complète (contenus de fichiers, sorties de build) part chez le fournisseur sans écran « ce qui sort » | M | Écran IA avec encadré « ce qui quitte l'appareil » et lien vers la suppression des données |
| R-07 | REF-124 | Protection anti-injection honnête mais incomplète : le prompt est une atténuation, la porte de permission est le vrai conteneur ; le marquage du contenu réseau existe | i | Reprendre les **trois** : règle d'ancrage dans le prompt + marquage du contenu réseau + porte de permission sur les mutants (le cahier des charges l'impose) |
| R-08 | REF-006 | Barre supérieure de l'atelier à 10+ contrôles même en compact ; chip de variante calculée en différé (première frame sans l'information) | m | Barre à 7 contrôles max en compact (retour, tiroir, titre, enregistrer, exécuter, ⋯) ; l'état de mode est statique (connu au lancement) |
| R-09 | REF-009 | Le retour du pager d'onboarding ne pagine pas en arrière sur Android (choix multiplateforme documenté) | m | Notre assistant : le retour système pagine en arrière (une page = un fragment de l'assistant, pile native) |
| R-10 | REF-010 | Surface applicative très large (store, publicité, social, saisonnier) : chaque couche est une surface de test et un coût de maintenance | i | Périmètre borné : IDE + deux modes + IA ; aucune boutique, aucune pub, aucun réseau social |
| R-11 | REF-020, REF-021 | L'éditeur et la barre de symboles sont remarquables mais **recodés en interne** — l'investissement éditeur est entier, donc peu réutilisable par d'autres produits | i | Nous consommons les briques de l'auteur (éditeur, LSP, aperçu XML) : la valeur ajoutée se concentre sur l'intégration et l'atelier |
| R-12 | REF-102 | Le tier gratuit maison via proxy propriétaire ajoute un intermédiaire qui voit la conversation (PRIVACY le documente honnêtement) | i | Aucun tier maison : l'utilisateur apporte sa clé ou utilise une passerelle locale |
| R-13 | REF-121 | La gestion des quotas (trois variétés de 429, pacer préventif, attente annulable) est l'état de l'art — à reprendre | i | Reprendre intégralement en L7 (analyse-ia §5) |
| R-14 | REF-113 | L'ingénierie de cache de prompt pilote toute l'architecture IA : borner à l'arrivée, hystérésis, décisions permanentes | i | Reprendre intégralement (L7) |
| R-15 | REF-117, REF-118 | Porte de permission unique + annulation par tour **avec aveu honnête de l'irréversible** : la sécurité d'action est au niveau | i | Reprendre intégralement (L7) |
| R-16 | REF-002, REF-006 | Le traitement clavier/orientation (breakpoint mesuré barres visibles, insets bruts, pli des pliables) élimine les bascules de layout au clavier | i | Reprendre : mesure stable de la largeur, `configChanges`, insets appliqués à la main |
| R-17 | REF-013 | Trois mécanismes de couleur superposés (accents prédéfinis + seed dynamique + couleur personnalisée) : triple surface de test | m | Un seul mécanisme : thèmes clair/sombre suivis du système (+ jeu de couleurs d'éditeur paramétrable plus tard) |
| R-18 | REF-125 | Complétion inline IA et génération de commit absentes : deux différenciateurs libres | i | Suite possible (limites-et-suite) — ne pas lier la boucle agent à l'éditeur en L7 |

**Bilan de la partie 1.** Le produit de référence est excellent sur l'ergonomie de l'atelier
(REF-006, REF-021), l'ingénierie de contexte IA (REF-113) et la sécurité d'action
(REF-117, REF-118) ; il est structurellement faible sur l'accessibilité tactile (R-01), la
complexité de navigation (R-03) et la confidentialité des clés (R-05). Notre position :
reprendre les mécanismes éprouvés (porte de permission, cache de prompt, dock gestuel,
états vides différés), corriger les faiblesses identifiées, et borner le périmètre là où la
référence s'est dispersée (store, pub, social).

## Partie 2 — Revue de la génération 1 (production du propriétaire)

> Conformément à la consigne reçue le 2026-11-20, la génération 1 n'a **pas** été
> consultée pour cette revue : le périmètre porte sur le produit de référence et le
> cahier des charges. Ce qui suit est la revue de **positionnement** découlant du
> cahier des charges lui-même (moteur interne remplacé par Forge, briques de l'auteur à
> consommer). L'inventaire détaillé garde/adapte/remplace/abandonne est différé et le
> sera à la première consultation autorisée (voir `inventaire-generation-1.md`).

| # | Constat (positionnement, indépendant d'une lecture du code) | Gravité | Ce que nous faisons |
|---|---|---|---|
| G-01 | Le moteur de build interne de la génération 1 fait double emploi avec Forge (cahier des charges §4.8) : deux moteurs concurrents dans une application, c'est deux jeux de diagnostics, deux caches, deux surfaces de test | M | Le mode Forge **remplace** le moteur interne ; tout élément interne à conserver exige un ADR justifiant son intérêt face à Forge ou une demande formelle (`DEMANDES-FORGE.md`) |
| G-02 | Le compilateur Kotlin forké en VM ouvrière est un investissement lourd, propre à la génération 1, hors du périmètre du nouveau produit (Java 17 uniquement) | i | Non reconduit ; le besoin Kotlin (LSP) est couvert par la brique dédiée de l'auteur |
| G-03 | Aucun terminal intégré dans la génération 1 (constat du cahier des charges) : le mode Gradle exige un terminal réel (sessions, clavier, copier-coller, persistance) | M | Terminal en composant de premier ordre (L6), fork de la famille d'émulateurs de terminal libre, avec ADR de licence et de bootstrap |
| G-04 | Les briques de l'auteur (éditeur, LSP, aperçu XML) sont déjà publiées : les consommer en versions épinglées plutôt que de recoder | i | Versions lues dans la génération 1 lors de la phase de câblage (L5) ; JitPack est le canal constaté |
| G-05 | Les documents de la génération 1 contiennent de nombreuses mentions interdites par le cahier des charges §5 (noms de fichiers d'ADR compris) : tout apport doit être nettoyé | M | Règle de clean-room appliquée à tout ce qui vient de la génération 1 : le contenu passe, les mentions ne passent pas (contrôle automatique `tools/check-terms.sh`) |
| G-06 | Les rapports de phase de la génération 1 sont une mémoire précieuse mais formatée pour un autre découpage | m | Le nouveau découpage (lots L0-L9) repart de zéro ; la génération 1 reste en lecture seule comme référence d'expérience, hors du dépôt de travail |

**Bilan de la partie 2.** La génération 1 est une production riche dont le nouveau produit
hérite l'expérience, pas le code : le moteur cède la place à Forge (G-01), le compilateur
forké n'est pas reconduit (G-02), le terminal arrive (G-03), les briques passent du statut
« interne » à « dépendance versionnée » (G-04), et l'hygiène des mentions devient un
contrôle automatique (G-05).
