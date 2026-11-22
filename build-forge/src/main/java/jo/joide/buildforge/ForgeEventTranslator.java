package jo.joide.buildforge;

import java.util.function.Consumer;
import jo.forge.api.BuildEvent;
import jo.forge.client.EngineClient;

/**
 * Traduction des événements Forge ({@code jo.forge.api.BuildEvent}) vers les
 * événements JoIDE ({@code jo.joide.workbench.BuildEvent}) : l'atelier consomme un
 * seul contrat, quel que soit le mode (conception §4). Correspondance directe —
 * Forge expose déjà journal, états de tâches, progression et diagnostics structurés.
 */
public final class ForgeEventTranslator {

    private ForgeEventTranslator() {}

    /**
     * Traduit un événement Forge en événement JoIDE.
     *
     * @param engineEvent événement émis par {@link EngineClient}, jamais nul.
     * @return l'équivalent côté atelier, jamais nul.
     */
    public static jo.joide.workbench.BuildEvent translate(BuildEvent engineEvent) {
        if (engineEvent instanceof BuildEvent.LogEvent log) {
            return new jo.joide.workbench.BuildEvent.Log(
                    log.timestamp(), level(log.level()), log.message());
        }
        if (engineEvent instanceof BuildEvent.DiagnosticEvent diag) {
            return new jo.joide.workbench.BuildEvent.DiagnosticEvent(
                    new jo.joide.workbench.Diagnostic(
                            severity(diag.severity()), diag.file(), diag.line(),
                            diag.column(), diag.code(), diag.message(), diag.tool()));
        }
        if (engineEvent instanceof BuildEvent.TaskStateEvent task) {
            return new jo.joide.workbench.BuildEvent.TaskState(
                    task.taskName(), status(task.state()), task.durationMs());
        }
        if (engineEvent instanceof BuildEvent.ProgressEvent progress) {
            return new jo.joide.workbench.BuildEvent.Progress(
                    progress.tasksTotal(), progress.tasksDone());
        }
        if (engineEvent instanceof BuildEvent.BuildFinished finished) {
            return new jo.joide.workbench.BuildEvent.Finished(
                    finished.success(), finished.failedTask(),
                    finished.durationMs(), finished.message());
        }
        // Type inconnu (Forge ajouterait un variant) : journal de secours, jamais perdu.
        return new jo.joide.workbench.BuildEvent.Log(
                0, jo.joide.workbench.BuildEvent.Log.Level.DEBUG,
                engineEvent.toString());
    }

    /**
     * Abonné Forge qui traduit et relaie vers un consommateur JoIDE.
     *
     * @param engineClient client du moteur, jamais nul.
     * @param consumer     consommateur JoIDE des événements traduits, jamais nul.
     */
    public static Consumer<BuildEvent> relay(EngineClient engineClient,
            Consumer<jo.joide.workbench.BuildEvent> consumer) {
        return engineEvent -> consumer.accept(translate(engineEvent));
    }

    private static jo.joide.workbench.BuildEvent.Log.Level level(BuildEvent.LogLevel forge) {
        return switch (forge) {
            case DEBUG -> jo.joide.workbench.BuildEvent.Log.Level.DEBUG;
            case INFO -> jo.joide.workbench.BuildEvent.Log.Level.INFO;
            case WARN -> jo.joide.workbench.BuildEvent.Log.Level.WARNING;
            case ERROR -> jo.joide.workbench.BuildEvent.Log.Level.ERROR;
        };
    }

    private static jo.joide.workbench.Diagnostic.Severity severity(BuildEvent.Severity forge) {
        return switch (forge) {
            case INFO -> jo.joide.workbench.Diagnostic.Severity.INFO;
            case WARNING -> jo.joide.workbench.Diagnostic.Severity.WARNING;
            case ERROR -> jo.joide.workbench.Diagnostic.Severity.ERROR;
        };
    }

    private static jo.joide.workbench.BuildEvent.TaskState.Status status(BuildEvent.TaskState forge) {
        return switch (forge) {
            case STARTED -> jo.joide.workbench.BuildEvent.TaskState.Status.STARTED;
            case SUCCEEDED -> jo.joide.workbench.BuildEvent.TaskState.Status.SUCCEEDED;
            case FAILED -> jo.joide.workbench.BuildEvent.TaskState.Status.FAILED;
            case SKIPPED -> jo.joide.workbench.BuildEvent.TaskState.Status.SKIPPED;
            case UP_TO_DATE -> jo.joide.workbench.BuildEvent.TaskState.Status.UP_TO_DATE;
            // Forge v0.2.16+ : tâche relue du cache de build partagé (D-18) — le
            // moteur l'assimile à « rien à faire », l'atelier l'affiche à jour.
            case FROM_CACHE -> jo.joide.workbench.BuildEvent.TaskState.Status.UP_TO_DATE;
        };
    }
}
