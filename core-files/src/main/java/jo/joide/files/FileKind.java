package jo.joide.files;

/**
 * Type fin d'une entrée de l'arborescence (Lot 2 du tiroir gauche) :
 * {@link FileIcons} (Lot 1) donnait une catégorie stable par extension ; le
 * Lot 2 affine pour l'icône affichée (convention Android Studio, dessins
 * originaux) en tenant compte du **chemin** — pas seulement
 * du nom : {@code res/layout} n'est pas {@code res/values}, une racine de
 * sources n'est pas un dossier quelconque.
 *
 * <p>L'UI mappe chaque type vers un drawable — jamais d'extension ni de
 * règle de nommage codée en dur côté interface (règle du Lot 1).</p>
 */
public enum FileKind {
    /** Dossier simple (ni racine de sources ni package). */
    FOLDER,
    /** Dossier ouvert (état de rendu, même famille que FOLDER). */
    FOLDER_OPEN,
    /** Racine des sources principales ({@code src/main/java}). */
    SRC_ROOT,
    /** Racine des sources de test ({@code src/test/java}, {@code src/androidTest/java}). */
    TEST_ROOT,
    /** Dossier de package Java (sous une racine de sources). */
    PACKAGE,

    /** Classe Java (défaut tant que le contenu n'a pas été lu). */
    JAVA_CLASS,
    /** Classe Java abstraite (déduite de l'en-tête du fichier). */
    JAVA_ABSTRACT,
    /** Interface Java. */
    JAVA_INTERFACE,
    /** Énumération Java. */
    JAVA_ENUM,
    /** Annotation Java ({@code @interface}). */
    JAVA_ANNOTATION,
    /** Fichier Kotlin. */
    KOTLIN,

    /** {@code AndroidManifest.xml}. */
    MANIFEST,
    /** XML de layout ({@code res/layout*}). */
    RES_LAYOUT,
    /** XML de valeurs ({@code res/values*}). */
    RES_VALUES,
    /** Drawable XML ({@code res/drawable*}). */
    RES_DRAWABLE,
    /** XML générique (menu, navigation, autres). */
    XML,

    /** Script Gradle ({@code .gradle}, {@code .toml}, wrapper). */
    GRADLE,
    /** {@code .gitignore} / {@code .gitattributes}. */
    GITIGNORE,
    /** Texte lisible (README, LICENSE, {@code .md}, {@code .txt}). */
    TEXT,
    /** Configuration (JSON, YAML, properties, ini). */
    CONFIG,
    /** Image. */
    IMAGE,
    /** Archive. */
    ARCHIVE,
    /** Secret (jamais affiché en contenu par l'UI). */
    SECRET,
    /** Tout le reste (binaire ou inconnu). */
    UNKNOWN
}
