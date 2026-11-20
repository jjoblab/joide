package jo.joide.workbench;

/** Mode de travail sélectionné par l'utilisateur (cahier des charges §4.1). */
public enum WorkMode {
    /** Moteur de build maison, léger, rapide à installer. */
    FORGE,
    /** Environnement Linux complet, terminal intégré, projets Gradle existants. */
    GRADLE
}
