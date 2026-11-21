package jo.joide.workbench;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bus d'événements de l'espace de travail (Lot 0 du tiroir gauche) : **un
 * seul chemin de mise à jour** pour l'arborescence, la recherche, la
 * structure et l'état Git. Les actions sur fichiers, la sauvegarde, le build
 * et les mutations de l'agent IA y publient ; les onglets du tiroir s'y
 * abonnent. Pur JVM, thread-safe : les abonnés sont appelés sur le fil qui
 * publie (l'hôte Android déroute vers le fil UI s'il le faut).
 *
 * <p>Lot 0 : le bus existe et est testé ; le câblage complet (observation du
 * disque, build, agent IA) arrive avec les lots qui portent ces sources
 * d'événements.</p>
 */
public final class WorkspaceEvents {

    /** Événement de l'espace de travail (fermé : les types sont stables). */
    public sealed interface Event permits
            FileCreated, FileRenamed, FileDeleted, FileSaved, FileChangedOnDisk,
            BuildFinished, GitStateChanged, DiagnosticsChanged {}

    /** Un fichier a été créé. */
    public record FileCreated(Path file) implements Event {}

    /** Un fichier a été renommé/déplacé. */
    public record FileRenamed(Path from, Path to) implements Event {}

    /** Un fichier a été supprimé. */
    public record FileDeleted(Path file) implements Event {}

    /** Un fichier ouvert a été enregistré. */
    public record FileSaved(Path file) implements Event {}

    /** Un fichier a changé sur le disque (git, agent, éditeur externe). */
    public record FileChangedOnDisk(Path file) implements Event {}

    /** Un build vient de se terminer. */
    public record BuildFinished(boolean success) implements Event {}

    /** L'état Git du projet a changé (à relire). */
    public record GitStateChanged() implements Event {}

    /** Les diagnostics du projet ont changé (à relire). */
    public record DiagnosticsChanged() implements Event {}

    private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Abonne un consommateur aux événements.
     *
     * @return poignée de désabonnement (à appeler quand l'observateur meurt —
     *         jamais de fuite d'abonné).
     */
    public Runnable subscribe(Consumer<Event> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /** Publie un événement à tous les abonnés (fil de l'appelant). */
    public void publish(Event event) {
        for (Consumer<Event> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }

    /** Nombre d'abonnés courants (journalisation, tests). */
    public int subscriberCount() {
        return subscribers.size();
    }
}
