package jo.joide.diag;

/**
 * Niveau de sévérité d'une entrée de journal (portage CodeIDE §5.7, ADR-021).
 *
 * <p>L'ordre de déclaration est croissant en sévérité : il pilote le filtrage
 * par niveau minimal ({@link #isAtLeast}) appliqué à la fois par la
 * configuration d'exécution et par chaque destination (logcat, fichier).</p>
 */
public enum LogLevel {
    /** Diagnostique détaillé — actif en build debug ou réglage « Détaillé ». */
    DEBUG,
    /** Événement normal du cycle de vie (démarrage, build, session). */
    INFO,
    /** Situation anormale mais récupérable (repli, permission perdue). */
    WARN,
    /** Erreur : échec d'une opération, future entrée des rapports de plantage. */
    ERROR;

    /**
     * Ce niveau est-il au moins aussi sévère que {@code autre} ?
     *
     * @param autre niveau de référence (typiquement le niveau minimal actif)
     * @return {@code true} si l'entrée doit être traitée — et uniquement
     *         alors, le message est évalué (contrat du pipeline)
     */
    public boolean isAtLeast(LogLevel autre) {
        return ordinal() >= autre.ordinal();
    }
}
