# Analyse de design — produit de référence

> Phase 1 (analyse du design du produit de référence). Notes fonctionnelles clean-room : aucun
> code, layout, drawable, icône, chaîne ou valeur de ressource n'est recopié ; les comportements
> sont décrits fonctionnellement. Chaque constat renvoie à une entrée `REF-xxx` détaillée dans
> [`REFERENCES.md`](REFERENCES.md) (fichier de référence, plage de lignes, SHA épinglé
> `1b1a3052168a52f38a91ea03c87831a0d94c1fff`). Ce document ne cite jamais le produit de
> référence par son nom : voir `REFERENCES.md` pour l'épinglage.

Ce document alimente [`revue-reference.md`](revue-reference.md) (gravités) et
[`design-system.md`](design-system.md) (ce que nous retenons). La comparaison avec la
génération 1 est dans [`inventaire-generation-1.md`](inventaire-generation-1.md) : ce
document ne couvre que le produit de référence, la génération 1 n'y apparaît qu'en
rappels discrets quand un point existe déjà chez nous.

## 1. Cadre général

- **Architecture UI** : l'interface est un front-end **Compose pur** branché sur un **port
  unique** (backend abstrait) ; l'UI ne parle jamais directement aux compilateurs, moteurs de
  langage ou au système de build. Résultat constaté : l'UI se teste sur desktop sans Android
  (plusieurs centaines de tests UI comptés) et un nouvel hôte (iOS, CLI) n'écrit pas un écran.
  Notre équivalent Java : une couche `workbench` qui ne dépend que d'interfaces Java pures
  (REF-001).
- **Hôte Android** : une unique `ComponentActivity` sans fragments ; tout est Compose, la
  navigation est maison (enum d'écrans + transitions). Contraste direct avec nos règles (vues
  AndroidX + XML + ViewBinding, `Fragment`/`ViewModel` autorisés) : nous gardons notre
  socle, mais le principe du **port unique** est retenu.
- **Adaptation** : breakpoints mesurés « comme si les barres système étaient visibles » pour
  éviter le basculement de layout à l'ouverture du clavier en paysage (REF-002), manifeste
  avec `configChanges` large (pas de recréation à la rotation), edge-to-edge, insets bruts,
  pli des pliables respecté (panneau arrêté sur la charnière).

## 2. Inventaire des écrans et parcours

L'énumération d'écrans constatée couvre 36 destinations (accueil, création/import/export de
projet, atelier, hub, run, configuration de module, gestionnaires SDK/keystore/icônes,
paramètres et leurs sous-écrans, apprentissage, défis, store, publication, modération…). Le
périmètre **pertinent pour JoIDE** est restreint aux écrans de notre cahier des charges ;
les destinations communautaires/store/pub de la référence sont hors de notre périmètre
(décision : aucun réseau social intégré, aucune boutique, aucune publicité).

| Écran de la référence | REF | Ce qui est constaté | Notre position |
|---|---|---|---|
| Accueil / projets récents | REF-003 | Hub à onglets (projets / store / IA / leçons / défis) si store actif, sinon gestionnaire simple ; cartes de projet à une seule ligne de méta (type · modules · âge), asymétrie bouton principal + débordement, segments filtrants **avec comptes, même vides**, suppression confirmée par le nom du projet, carte « Reprendre » en pied de liste | Nous : accueil focalisé projets (pas d'onglets store/leçons) ; nous retenons les cartes à une ligne de méta, l'asymétrie primaire/débordement, la confirmation nommante, la carte « Reprendre » comme **seul élément saturé** de l'écran |
| Création de projet | REF-004 | 2 étapes : galerie de modèles (recherche + filtres par catégorie avec comptes, grille adaptative, regroupement hors recherche) puis écran de configuration **piloté par les paramètres déclarés du modèle** (texte / choix / bascule), bouton unique plein écran | Nous : même parcours en 2 étapes ; nous retenons le **formulaire piloté par descripteur de modèle** (un modèle apporte ses champs sans toucher l'UI) et la grille adaptative |
| Import de projet | REF-005 | Import et clonage Git depuis le bouton de débordement de l'accueil | Nous : import depuis l'accueil (sélecteur SAF + détection du type, voir conception §5) ; pas de clonage Git en L6 (consigné dans limites) |
| Atelier (téléphone) | REF-006 | Barre supérieure 52 dp hiérarchisée : actions urgentes inline (enregistrer, annuler/rétablir, exécuter), le reste replié dans un menu ⋯ ; onglets 40 dp à défilement horizontal avec chevron de débordement et fermeture ciblée ; tiroir fichiers à glissement gauche ; panneau IA à droite ; **dock de build bas à deux détentes** (barre → 60 % → plein) sensible au scroll imbriqué ; barre de symboles 46 dp au-dessus du clavier | Nous : même topologie (tiroir fichiers gauche, tiroir IA droit, dock bas) mais le dock bas de JoIDE est un **bottom sheet à sections Console / Problèmes / Journal / Logcat** sans doublon d'en-tête (règle maison) ; pas de détentes gestuelles à 3 niveaux en L1 (une seule détente 50 % + plein) |
| Console de build | REF-007 | La console vit dans le dock repliable ; le run a son écran plein (phase de build, erreurs **cliquables** (ouvre le fichier à la ligne), transcript coloré, vue tests avec re-run des échecs, barre stdin/EOF) | Nous : la **Console** est une section du bottom sheet (source : `BuildBackend`), erreurs cliquables vers l'éditeur ; l'installation/l'exécution de l'APK a son propre écran d'installation (conception §7) |
| Paramètres | REF-008 | Hub **à plat** (une page qui liste toutes les destinations, groupée par sections) puis rendu **générique** des pages depuis des descripteurs, avec scope app/projet | Nous : hub à plat retenu (mode de travail, briques, IA, stockage, à propos) avec pages réelles (pas de rendu générique : notre périmètre est borné) |
| Assistant premier lancement | REF-009 | Feuille plein écran, pages fixes, pager à progression segmentée, CTA final qui **lance le flux de création de projet** ; héros dessinés à la main (maquettes du produit, pas des captures) | Nous : notre premier lancement choisit le **mode de travail** (Forge / Gradle) : les pages présentent capacités, limites, tailles et espace disque de chaque mode, CTA « Installer ce mode » — l'installation suit, elle ne conclut pas l'assistant |
| Écrans ours (store, leçons, défis, publication, modération) | REF-010 | Hub social complet : boutique de modèles, parcours pédagogiques, défis, profils, modération, publicité native (11 emplacements nommés, tous sur des temps d'attente naturels) | Hors périmètre JoIDE. Nous ne retenons de cette famille que la **grille de décision « jamais au-dessus des actions »** comme règle pour d'éventuelles notifications de mise à jour |

## 3. Organisation de la navigation

- **Pas de bibliothèque de navigation tierce** : un enum d'écrans ordonné + transitions
  directionnelles (plus profond arrive de la droite, retour depuis la gauche) ; le sens
  avant/arrière est donné par l'ordinal **corrigé par un flag retour explicite** parce que des
  écrans s'ouvrent mutuellement dans les deux sens (REF-011).
- **Priorité du retour système** : le gestionnaire de retour le plus profond gagne — fermer
  une feuille avant de quitter un écran, jamais l'inverse ; dans l'atelier, une pile explicite
  « fermer les overlays dans l'ordre » avant de fermer le projet (REF-011).
- **Gestes** : tiroir fichiers à glissement sensible au scroll imbriqué ; dock à deux
  détentes ; overlay droit depuis le bord opposé (REF-006).
- **Retour de navigation de code** : historique Back/Forward des sauts (aller à la définition,
  usages, onglets) **au-dessus** du retour système — deux niveaux de retour qui ne se
  confondent pas ; déduplication par (fichier, ligne), offsets rebasés aux renommages
  (REF-012). Nous le retenons pour l'atelier.
- **Écueil constaté** : une fonction retour maison de 30 branches `when` pour 36
  destinations — à éviter par une **table de navigation parent/enfant déclarative** dès la
  conception (notre `docs/ecrans/` fixe le parent de chaque écran).

## 4. Principes visuels

- **Couleur** : rôles Material 3 pour l'UI ; **palette dédiée aux concepts d'IDE**
  (éditeur, console, syntaxe, git, translucides) séparée de la palette UI (REF-013). Nous
  retenons cette séparation à l'identique (design-system §2).
- **Thèmes clair/sombre** : suit le système ; couleurs de l'éditeur éditables/importables
  avec prévisualisation live. Trois mécanismes de couleur superposés constatés (accents
  prédéfinis + seed dynamique + couleur personnalisée) — nous en retenons **un seul** :
  thèmes clair/sombre suivis du système + jeu de couleurs d'éditeur paramétrable plus tard.
- **Typographie** : échelle complète en `sp`, tracking négatif sur les grandes tailles,
  poids léger en display (la présence vient de la taille, le contraste de poids fait la
  hiérarchie), style « eyebrow » (petites majuscules larges) séparé du label de phrase,
  **aucun conteneur de texte à hauteur fixe** (2× de police wrappe au lieu de couper)
  (REF-014). Retenu tel quel.
- **Formes** : échelle de coins plus ronde que le M3 stock ; **contraste de rayon
  grand/petit sur un même élément** (cartes à coins alternés) comme signature (REF-015).
  Retenu (grand rayon contenant / petit rayon contenu).
- **Mouvement** : tokens de durée nommés ; ressorts **spatiaux** sous-amortis (position,
  taille) et ressorts d'**effets** critiques (couleur, alpha — « une couleur qui overshoot
  se lit comme un scintillement ») (REF-016). Retenu comme règle.
- **Hiérarchie de l'accueil** : eyebrow + titre display ; boutons asymétriques (primaire
  pleine largeur + carré tonal) ; cartes neutres où **la couleur vit dans la tuile, jamais
  dans le fond de liste** ; **un seul élément saturé par écran** (la carte « Reprendre »)
  (REF-003). Retenu comme discipline.
- **États vides** : encadré pointillé + phrase + action concrète, **retardé jusqu'à
  confirmation que la liste est chargée** (pas de flash « aucun projet » pendant la lecture
  disque) ; éditeur sans fichier : 6 actions concrètes + fichiers récents (REF-017).
  Retenu.
- **États d'erreur** : **strip d'avis unique** fusionné en haut de l'atelier — une ligne
  pour le simple, une carte repliée pour le complexe, compte cliquable pour un run
  d'avertissements (REF-018). Nous retenons la fusion : chez nous cette strip devient la
  zone de notification de l'atelier (compatibilité de mode, limites Forge affichées
  honnêtement, avertissements de stockage).
- **Retours d'action** : snackbar avec **durée différenciée selon la réversibilité**
  (8 s avec annulation, 5 s sans), optimisme local avec retour arrière sur échec réseau
  (REF-019). Retenu.
- **Densité** : gouttière 20 dp, largeur max 720 dp centrée, top bar 52 dp, barre de
  symboles 46 dp — densité « IDE desktop-like » assumée sur les listes techniques. Nous
  retenons les gabarits avec **une limite d'accessibilité** (ci-dessous).

## 5. Éditeur

- **Architecture** : document rope/indexé par lignes, re-tokenisation de la seule ligne
  modifiée, rendu des lignes visibles, clavier via `InputConnection` ; **seuil « gros
  fichier »** (2,5 M caractères / 50 000 lignes) au-delà duquel les passes lourdes se
  coupent mais l'édition reste (REF-020). Nous n'implémentons pas notre éditeur (briques
  de l'auteur : éditeur et coloration par la brique dédiée, LSP par la brique dédiée,
  aperçu XML par la brique dédiée) ; le **seuil « gros fichier »** est retenu comme
  exigence à demander aux briques ou à documenter.
- **Barre de symboles** au-dessus du clavier : caps défilants, **flick-up** tape
  l'alternatif du cap (une touche = deux symboles), **maintien-glisser = trackpad de
  caret** avec retour haptique, jeux par langage, épinglables ; aucun cap n'utilise un
  `clickable` qui volerait le focus à l'éditeur (REF-021). Retenu comme spécification
  d'exigence pour notre intégration (conception §6).
- **Sélection** : barre flottante copier/couper/coller/tout-sélectionner **ancrée au bout
  actif de la sélection** ; presse-papiers historique (REF-021).
- **Recherche** : barre find/replace **dans le fichier** dockée en haut, opaque (le
  translucide laissait lire le code à travers), compteur, options sur seconde rangée
  sous ~520 dp ; recherche projet à onglets dont **l'état survit à la navigation vers un
  résultat** (état hoisté hors du panneau) ; palette de commandes à portées (REF-022).
  Retenu.
- **Gutter/complétion/squiggles** : parité desktop constatée (numéros de ligne, marques
  d'erreur, appariement de parenthèses, pliage, pinches de zoom police) — pour nous c'est
  le domaine des briques de l'auteur ; nous spécifions les exigences dans la conception.

## 6. Accessibilité

- **contentDescription obligatoire dans le composant bouton-icône de base** : le paramètre
  est non-null par construction — 149 usages de description nulle dénombrés, quasi tous
  décoratifs (REF-023). Retenu (règle de lint maison + revue).
- **Item de navigation = conteneur entier = un seul nœud d'accessibilité fusionné**
  (correction d'un bug « le label sous l'icône ne recevait pas le tap ») (REF-023).
  Retenu.
- **Tailles tactiles** : le point noir constaté — boutons de barre **densifiés sous les
  48 dp recommandés** (34 dp constatés) au nom de la densité desktop, compromis documenté
  chez eux (REF-024). Nous : **cible 48 dp minimale**, densité obtenue par la hiérarchie
  et le regroupement, jamais par des cibles rétrécies ; au pire 44 dp **avec zone
  tactile invisible étendue**.
- **Taille de texte** : tout en `sp`, aucun conteneur à hauteur fixe ; l'éditeur a son
  propre facteur de zoom indépendant du système (REF-024). Retenu.
- **Contraste** : palettes syntaxe distinctes claire/sombre ; **les couleurs du transcript
  de console sont conscientes du thème** pour rester lisibles sur fond clair OU sombre
  (REF-024). Retenu comme exigence Console/Logcat.
- **Reduce-motion** : géré « aux points d'appel » (inégal par construction) — nous plaçons
  la porte dans le **token de mouvement** dès le départ (REF-025).
- **RTL** : miroitement explicite des tiroirs constaté (test arabe complet). Notre UI est
  française ; l'éditeur de code reste LTR inconditionnel (retenu), le RTL n'est pas un
  objectif L1-L9 (consigné dans limites).

## 7. Bilan de l'analyse

Points que nous reprenons : port unique UI/moteur, retour directionnel + priorité du
retour, dock bas gestuel, barre de symboles à flick + trackpad (exigence brique), strip
d'avis unique, états vides différés, typographie sp + tracking, un seul élément saturé,
couleur dans la tuile pas dans le fond, snackbar à durée différenciée, seuil gros fichier,
breakpoint mesuré barres visibles.

Points que nous ne reprenons pas : cibles tactiles densifiées (34 dp), reduce-motion par
point d'appel, trois mécanismes de couleur superposés, décor saisonnier, navigation à 36
destinations avec retour en cascade de 30 branches, onglets store/leçons/défis (hors
périmètre).

Le produit de référence est fort sur l'ergonomie de l'atelier (gestuelle, hiérarchie,
retours) et sur l'ingénierie d'accessibilité de l'éditeur ; il est structurellement limité
par des choix de densité et une surface applicative très large (store, pub, social) que
nous n'adoptons pas. Les écarts voulus sont consignés dans `REFERENCES.md`, colonne
statut.
