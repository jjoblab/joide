# Licences — composants tiers du mode Gradle et de l'application

> Phase 3 (cahier des charges §4.3 point 1). Analyse des licences des composants de la
> famille d'émulateurs de terminal libre : ce qui est réutilisable, sous quelles
> conditions, et ce que cela impose à JoIDE. Le produit de référence (GPL-3.0,
> clean-room) est traité dans `REFERENCES.md` et n'apparaît ici que comme rappel de
> périmètre. Ce document ne le nomme jamais.

## 0. Cadre légal général

| Composant | Licence | Conclusion |
|---|---|---|
| JoIDE (ce dépôt) | **Apache-2.0** | Cohérent avec Forge (Apache-2.0) et les briques de l'auteur |
| Forge (`jjoblab/forge`) | Apache-2.0 | Consommable comme bibliothèque versionnée, sans obligation copyleft |
| Briques de l'auteur (céleris, code-lsp, LayoutLib) | à vérifier fichier par fichier à l'épinglage (L5) | Publiées par le propriétaire ; l'épinglage et les licences seront consignés à L5 dans ce tableau |
| Produit de référence | GPL-3.0-or-later + exception de linkage | Clean-room : aucune réutilisation de code (voir `REFERENCES.md`) |
| Bibliothèques AndroidX / Material 3 | Apache-2.0 | Usage standard |
| `security-crypto` (clés IA) | Apache-2.0 | Usage standard |

## 1. La famille du terminal libre (application Termux, bibliothèque d'émulateur, paquets)

Le mode Gradle veut un terminal intégré « basé sur un fork de Termux » (cahier des
charges §4.3). Trois couches distinctes, **trois licences différentes** :

### 1.1 L'application Termux elle-même

- Licence : **GPL-3.0-or-later** (fichier LICENSE du dépôt de l'application).
- Ce qu'elle contient : activités, service de premier plan, gestion des sessions,
  vues de terminal, clavier étendu, bootstrap.
- **Réutilisation dans JoIDE** : fork **dans ce dépôt**, code réécrit/adapté sous
  `jo.joide.terminal.*`. Conséquence directe : **le code forké reste GPL-3.0** (la GPL
  interdit de changer la licence d'une œuvre dérivée), et il est **isolé dans le module
  `build-gradle`** (et si besoin un module `terminal` dédié). Le reste de JoIDE
  (Apache-2.0) **dépend** du module GPL sans **lier** contre lui par une frontière
  nette : le terminal s'exécute dans **son propre processus** (le service du terminal),
  communique par IPC/intents et fichiers ; l'interface AndroidX de JoIDE ne contient
  aucune classe du fork. Position assumée : le module GPL est une œuvre dérivée du
  fork, le reste de JoIDE est une œuvre distincte qui l'orchestre par des mécanismes
  système (processus séparé + intents), pas par linkage d'œuvre. Un avocat prudent
  exigerait : GPLv3 §5 « aggregate » vs « combined work » — notre lecture : un process
  séparé activé par intent est l'équivalent mobile de deux programmes communiquant
  « at arm's length », la FAQ GPL le traite comme deux œuvres séparées. La notice GPL
  du module terminal est conservée, le code source complet du fork est publié dans ce
  dépôt public (obligation GPLv3 §5 sur la distribution de l'APK), et l'écran « À
  propos » de JoIDE liste le composant avec sa licence et l'URL du dépôt.
- **Conditions remplies dans ce dépôt** : notice de licence dans
  `build-gradle/LICENSE-TERMINAL.md` (nommant l'œuvre d'origine, ce qui est autorisé
  car Termux n'est **pas** le produit de référence et le cahier des charges §5.4 le
  permet expressément : « les autres projets (Termux, Forge, la génération 1, les
  briques…) peuvent être cités ouvertement »), code source complet publié, écran
  « À propos » exhaustif.

### 1.2 La bibliothèque d'émulateur de terminal (`terminal-view` / `terminal-emulator`)

- Licence : **GPL-3.0** également dans le dépôt d'origine (les deux modules y sont
  GPLv3). Le fork conserve la GPL et vit dans le même module `build-gradle` — mêmes
  obligations, mêmes protections (processus séparé).
- Conséquence d'architecture : la **vue** de terminal (widget) vit dans le module
  `build-gradle` avec l'émulateur ; l'atelier JoIDE n'héberge pas la vue, il la
  franchit par une activité du module (un écran du terminal, pas un fragment dans
  l'atelier). C'est la traduction technique de la frontière de licence.

### 1.3 Les paquets du bootstrap (environnement de base)

- Licence : **varie par paquet** (chacun garde la sienne : bash = GPL-3.0+,
  coreutils = GPL-3.0+, dpkg = GPL-2.0+, busybox si utilisé = GPL-2.0+, utilitaires
  BSD = licences BSD, etc.). Le **répertoire d'assemblage** du bootstrap upstream est
  un métapaquet ; redistribuer un bootstrap assemblé = redistribuer des binaires de
  paquets sous leurs licences respectives.
- **Obligation pour nous** : fournir les sources correspondantes des paquets GPL que
  nous distribuons (L'« offersource » de GPLv3 §6 : la source est celle que nous
  incluons ou que le dépôt amont publie ; l'écran « À propos » pointe vers les dépôts
  de sources amont et notre manifeste de composants embarque les URL de source par
  paquet). Nous réutilisons la chaîne **du packaging maison existant**
  (`codeide-packages`, dépôt du propriétaire) : un bootstrap déjà construit avec un
  préfixe de paquet dédié, ce qui élimine le plus gros risque de §1.4 ci-dessous et
  nous met dans une situation de redistribution documentée.
- Registre : `build-gradle/NOTICE-PAQUETS.md` (paquet, version, licence, URL de
  source) — tenu à jour à chaque évolution du bootstrap.

### 1.4 Le préfixe (traité en détail dans ADR-006)

Le bootstrap officiel est construit pour le préfixe `/data/data/com.termux/files/usr`.
Deux options : bootstrap reconstruit pour le préfixe de JoIDE (ce que le packaging
maison du propriétaire fournit déjà), ou rootfs via proot. Décision, analyse et
risques : **ADR-006** (recommandation : bootstrap dédié, déjà disponible chez nous).

## 2. JDK, Gradle, SDK Android (mode Gradle)

| Composant | Licence | Redistribution par JoIDE |
|---|---|---|
| JDK (distribution à définir, ex. Temurin = licences publiques EAESU/GPLv2+CE pour OpenJDK) | OpenJDK est GPLv2 **avec Classpath-exception-2.0** | Redistribution de l'archive autorisée par la CE (même mécanisme que l'exception du produit de référence) ; notice + URL de source. Choix de la distribution : ADR (à consigner à L6) |
| Distribution Gradle | Apache-2.0 | Redistribution libre ; notice |
| Plateformes Android (`android.jar`, `platforms/`) | SDK Terms (fichier `platforms/android-*` : Apache-2.0 pour la **plupart** des composants, mais les Terms n'accordent pas de licence de redistribution générale : « subject to the terms ») | **Non redistribuable par nous en l'état** : la position de Forge (docs/distribution.md §7 : garde juridique, pas de publication de `platform-*.zip` tant que les conditions ne sont pas validées) est **reprise telle quelle** — l'utilisateur accepte les licences du SDK dans JoIDE et le composant est téléchargé depuis les serveurs de l'éditeur (DL via nos URL vers `dl.google.com`), vérifié par SHA-256 ; la redistribution directe est refusée par ADR-010 |
| Build-tools / `aapt2` (arm64) | SDK Terms + composants Apache-2.0 | Idem : téléchargement depuis la source officielle, somme épinglée, **pas de re-publication**. Le `aapt2` arm64 natif est le point ouvert (Forge a le même : « construction AOSP dédiée ») — besoin communiqué dans `DEMANDES-FORGE.md` |
| Outils du shell (git, etc.) | par paquet | Comme §1.3 |

## 3. Conclusion et obligations résumées

1. **Le mode Gradle embarque du GPL** (application + émulateur forkés, paquets du
   bootstrap) : assumé, isolé dans `build-gradle`, sources publiées dans ce dépôt,
   notices dans `LICENSE-TERMINAL.md` / `NOTICE-PAQUETS.md`, écran « À propos »
   exhaustif. Le reste de JoIDE reste Apache-2.0 et ne lie pas contre le GPL (processus
   séparé).
2. **Les composants du SDK Android ne sont pas redistribués** : licences acceptées par
   l'utilisateur dans l'application, téléchargement depuis les serveurs de l'éditeur,
   sommes épinglées (ADR-010). C'est la position de Forge, reprise.
3. **JDK et Gradle** : redistribuables (Apache-2.0 / CE), notices et URL de source dans
   le manifeste des composants.
4. **Les briques de l'auteur** : épinglées à L5 avec leur licence consignée ici même.
5. Ce document est le registre vivant : chaque évolution du bootstrap, du JDK ou du SDK
   met à jour les notices correspondantes.
