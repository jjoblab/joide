package jo.joide.ai;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Porte de permission **interactive** de la coquille (L8, REF-117) : l'appro-
 * bation d'une mutation arrive avec le **diff dans l'invite** (aperçu
 * avant/après fourni par la boucle), trois décisions (refuser / permettre une
 * fois / permettre pour la session), la fermeture du dialogue vaut **refus**
 * (défaut sûr). Mode persisté (préférences IA).
 *
 * <p>Pur JVM : la décision vient d'un consommateur posé par l'UI (le dialogue
 * Android) ; tant qu'aucun décideur n'est posé, la porte **refuse** (défaut
 * sûr), comme la fermeture du dialogue.</p>
 */
public final class InteractivePermissionGate implements PermissionGate {

    /** Décision rendue par le décideur (dialogue). */
    public enum Decision { DENY, ALLOW_ONCE, ALLOW_SESSION }

    /** Décideur : affiche l'invite (diff dedans) et rend la décision. */
    @FunctionalInterface
    public interface Decider {

        /**
         * @param request  la demande (outil, chemin, aperçu avant/après).
         * @param onAnswer à appeler exactement une fois avec la décision.
         */
        void prompt(PermissionRequest request, Consumer<Decision> onAnswer);
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ASK_EACH);
    private volatile boolean sessionAllowAll;
    private final AtomicReference<Decider> decider = new AtomicReference<>();

    /** Pose le décideur (l'UI de la coquille ; null = défaut sûr : refus). */
    public void setDecider(Decider value) {
        decider.set(value);
    }

    /** Mode persisté (paramètres IA : les trois modes de la porte unique). */
    public void setMode(Mode value) {
        mode.set(value);
    }

    @Override
    public Mode mode() {
        return mode.get();
    }

    /** Nouvelle session : « permettre pour la session » expire. */
    public void resetSession() {
        sessionAllowAll = false;
    }

    @Override
    public boolean authorize(PermissionRequest request) {
        seen.add(request);
        return switch (mode.get()) {
            case AUTO_ACCEPT -> true;
            case PLAN_ONLY -> false;
            case ASK_EACH -> {
                if (sessionAllowAll) {
                    yield true;
                }
                Decider active = decider.get();
                if (active == null) {
                    yield false; // fermeture du dialogue = refus (défaut sûr)
                }
                CompletableFuture<Decision> answer = new CompletableFuture<>();
                try {
                    active.prompt(request, answer::complete);
                } catch (Exception e) {
                    yield false; // un décideur cassé refuse (défaut sûr)
                }
                try {
                    Decision decision = answer.get();
                    if (decision == Decision.ALLOW_SESSION) {
                        sessionAllowAll = true;
                    }
                    yield decision != Decision.DENY;
                } catch (Exception e) {
                    yield false; // interruption/timeout = refus (défaut sûr)
                }
            }
        };
    }

    /** File des demandes vues (journalisation des tests, jamais en production). */
    private final ConcurrentLinkedQueue<PermissionRequest> seen = new ConcurrentLinkedQueue<>();

    /** Demandes passées par la porte (aperçu du dialogue). */
    public java.util.List<PermissionRequest> seenRequests() {
        return java.util.List.copyOf(seen);
    }
}
