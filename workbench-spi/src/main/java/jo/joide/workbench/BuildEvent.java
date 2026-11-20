package jo.joide.workbench;

/** Événement d'un build, commun aux deux modes (conception §4). */
public sealed interface BuildEvent permits
        BuildEvent.Log, BuildEvent.TaskState, BuildEvent.Progress,
        BuildEvent.DiagnosticEvent, BuildEvent.Finished {

    /** Entrée de journal (section Console). */
    record Log(long timestamp, Level level, String message) implements BuildEvent {

        /** Niveau de journal. */
        public enum Level { DEBUG, INFO, WARNING, ERROR }
    }

    /** Changement d'état d'une tâche du graphe (section Console). */
    record TaskState(String task, Status status, long durationMs) implements BuildEvent {

        /** État d'une tâche. */
        public enum Status { STARTED, SUCCEEDED, FAILED, SKIPPED, UP_TO_DATE }
    }

    /** Progression globale (barre de l'atelier). */
    record Progress(int tasksTotal, int tasksDone) implements BuildEvent {}

    /** Diagnostic structuré (section Problèmes + saut éditeur). */
    record DiagnosticEvent(Diagnostic diagnostic) implements BuildEvent {}

    /** Fin de build : toujours le dernier événement. */
    record Finished(boolean success, String failedTask, long durationMs, String message)
            implements BuildEvent {}
}
