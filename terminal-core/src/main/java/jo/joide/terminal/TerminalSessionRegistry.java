package jo.joide.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registre des sessions du terminal intégré (mode Gradle, conception §7) :
 * plusieurs sessions simultanées, création sur le thread principal (contrainte
 * héritée du TerminalSession de Termux — MainThreadHandler sans Looper), aperçu
 * borné de la sortie pour le tiroir, sessions mortes visibles jusqu'à fermeture
 * explicite.
 *
 * <p>Découpage repris de l'ADR-0035 de CodeIDE (consigne du propriétaire) :
 * un port de métadonnées pur ({@code TerminalSessionRepository}) consommable
 * sans type Termux, la colle réelle vivant côté coquille Android. La logique
 * de ce registre est testable sur JVM avec des coquilles scriptées.</p>
 */
public final class TerminalSessionRegistry {

    /** Métadonnées pures d'une session (tiroir, sans type de vue). */
    public record SessionSummary(String id, String title, boolean alive,
            String lastOutputPreview) {}

    /** Coquille d'une session : création/sortie/fin, scriptée en test. */
    public interface Shell {
        /** Écrit des octets dans l'entrée de la coquille. */
        void write(String input);

        /** Tue la coquille. */
        void kill();
    }

    /** Fabrique de coquilles (production : Termux ; test : scriptée). */
    public interface ShellFactory {
        Shell create(String id, String initialDirectory, Consumer<String> onOutput,
                Runnable onExit);
    }

    private final ShellFactory factory;
    private final Map<String, SessionRecord> sessions = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final int PREVIEW_LIMIT = 160;

    private record SessionRecord(String id, String title, Shell shell,
            StringBuilder recentOutput, boolean[] alive) {

        SessionRecord {
            java.util.Objects.requireNonNull(id);
        }
    }

    /** Nouveau registre sur une fabrique de coquilles. */
    public TerminalSessionRegistry(ShellFactory factory) {
        this.factory = java.util.Objects.requireNonNull(factory);
    }

    /** Abonnement aux changements (tiroir : liste, sorties). */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Crée une session (sur le thread de l'appelant — le principal côté appareil).
     *
     * @param initialDirectory répertoire de départ (projet ou HOME).
     * @param title            titre affiché dans le tiroir.
     * @return l'identifiant de la session créée.
     */
    public String newSession(String initialDirectory, String title) {
        String id = "session-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        boolean[] alive = {true};
        StringBuilder recentOutput = new StringBuilder();
        Shell shell = factory.create(id, initialDirectory, output -> {
            synchronized (recentOutput) {
                recentOutput.append(output);
                int excess = recentOutput.length() - PREVIEW_LIMIT;
                if (excess > 0) {
                    recentOutput.delete(0, excess);
                }
            }
            notifyChanged();
        }, () -> {
            alive[0] = false;
            notifyChanged();
        });
        sessions.put(id, new SessionRecord(id, title, shell, recentOutput, alive));
        notifyChanged();
        return id;
    }

    /** Sessions connues (vives et mortes : fermeture explicite requise). */
    public List<SessionSummary> summaries() {
        synchronized (this) {
            List<SessionSummary> list = new ArrayList<>();
            for (SessionRecord record : sessions.values()) {
                String preview;
                synchronized (record.recentOutput()) {
                    preview = record.recentOutput().toString();
                }
                list.add(new SessionSummary(record.id(), record.title(),
                        record.alive()[0], preview));
            }
            return List.copyOf(list);
        }
    }

    /** Écrit dans une session (retourne faux si introuvable). */
    public boolean write(String id, String input) {
        SessionRecord record = sessions.get(id);
        if (record == null) {
            return false;
        }
        record.shell().write(input);
        return true;
    }

    /** Tue une session (elle reste visible comme morte jusqu'à fermeture). */
    public boolean kill(String id) {
        SessionRecord record = sessions.get(id);
        if (record == null) {
            return false;
        }
        record.shell().kill();
        return true;
    }

    /** Ferme (retire du tiroir) une session morte ou vive. */
    public boolean close(String id) {
        SessionRecord removed = sessions.remove(id);
        if (removed == null) {
            return false;
        }
        removed.shell().kill();
        notifyChanged();
        return true;
    }

    private void notifyChanged() {
        listeners.forEach(Runnable::run);
    }
}
