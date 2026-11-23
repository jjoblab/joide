package jo.joide.workbench;

/** Événement d'un build, commun aux deux modes (conception §4). */
public sealed interface BuildEvent permits
        BuildEvent.Log, BuildEvent.TaskState, BuildEvent.Progress,
        BuildEvent.DiagnosticEvent, BuildEvent.Download, BuildEvent.Heartbeat,
        BuildEvent.Finished {

    /** Entrée de journal (section Console). */
    record Log(long timestamp, Level level, String message) implements BuildEvent {

        /**
         * Niveau de journal. {@code LIFECYCLE} (Forge v0.2.26, protocole 4) : les
         * lignes de VIE de la console de build composées par le MOTEUR — lignes de
         * tâches, verdict — l'équivalent du niveau éponyme de Gradle, entre INFO
         * et avertissement. La sévérité ordonnée est {@code DEBUG < INFO <
         * LIFECYCLE < WARNING < ERROR} (l'ordinal suit cet ordre).
         */
        public enum Level { DEBUG, INFO, LIFECYCLE, WARNING, ERROR }
    }

    /** Changement d'état d'une tâche du graphe (section Étapes). */
    record TaskState(String task, Status status, long durationMs) implements BuildEvent {

        public enum Status { STARTED, SUCCEEDED, FAILED, SKIPPED, UP_TO_DATE }
    }

    /** Progression globale (barre de l'atelier). */
    record Progress(int tasksTotal, int tasksDone) implements BuildEvent {}

    /** Diagnostic structuré (section Problèmes + saut éditeur). */
    record DiagnosticEvent(Diagnostic diagnostic) implements BuildEvent {}

    /**
     * Progression d'un téléchargement de dépendance. Face à un moteur protocole 4,
     * la ligne console « ↓ fichier (taille) » est DÉJÀ composée par le moteur :
     * l'événement reste pour la représentation (pastille, barre) — jamais une
     * seconde ligne. Face à un moteur plus ancien, l'atelier la compose (INFO).
     *
     * @param resource fichier téléchargé (nom court lisible).
     * @param bytesDownloaded octets reçus à ce stade.
     * @param bytesTotal taille annoncée, ou {@code -1} si inconnue.
     * @param done vrai quand le téléchargement est terminé.
     */
    record Download(String resource, long bytesDownloaded, long bytesTotal,
            boolean done) implements BuildEvent {}

    /**
     * Pulsation d'une tâche longue : la tâche est VIVANTE (nom, dernier message
     * de progression, temps écoulé) — l'atelier peut écrire une ligne vivante,
     * le silence n'est plus interprétable comme un plantage.
     *
     * @param task nom de la tâche en cours.
     * @param message dernier message de progression (peut être vide).
     * @param elapsedMs temps écoulé depuis le début de la tâche.
     */
    record Heartbeat(String task, String message, long elapsedMs) implements BuildEvent {}

    /** Fin de build : toujours le dernier événement. */
    record Finished(boolean success, String failedTask, long durationMs, String message)
            implements BuildEvent {}
}
