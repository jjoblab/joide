package jo.joide.workbench;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Activité de build (lot L10, d'après la référence) : le sémaphore « une compile
 * tourne-t-elle ». Les travaux d'arrière-plan — indexation en tête — l'attendent
 * pour ne pas voler de cycles au build de l'utilisateur ; un build n'est JAMAIS
 * ralenti par l'arrière-plan.
 *
 * <p>Pur JVM, thread-safe : {@link #set(boolean)} depuis le chemin de build (début,
 * fin, échec — faux forcé), {@link #awaitIdle(long)} depuis les travaux de fond
 * (attente bornée : au timeout, le travail décide — l'attente n'est pas un
 * blocage). Le test L10 prouve : bascule, attente, timeout, réveil à la fin du
 * build.</p>
 */
public final class BuildActivity {

    private volatile boolean compiling;
    private final Object monitor = new Object();
    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    /** Un build tourne-t-il maintenant ? */
    public boolean isCompiling() {
        return compiling;
    }

    /**
     * Pose l'état (début/fin de build). À faux, les attentes sont réveillées. Les
     * auditeurs (statut affichable) sont appelés sur le fil de l'appelant.
     */
    public void set(boolean compiling) {
        this.compiling = compiling;
        synchronized (monitor) {
            if (!compiling) {
                monitor.notifyAll();
            }
        }
        for (Consumer<Boolean> listener : listeners) {
            listener.accept(compiling);
        }
    }

    /**
     * Attend que plus aucun build ne tourne.
     *
     * @param timeoutMs borne d'attente (au-delà : rend la main — le travail de fond
     *                  décide de céder ou de continuer ; jamais de blocage infini).
     * @return false si le timeout a été atteint (un build tourne toujours).
     */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (monitor) {
            while (compiling) {
                long restant = deadline - System.currentTimeMillis();
                if (restant <= 0) {
                    return false;
                }
                monitor.wait(restant);
            }
        }
        return true;
    }

    /** Abonne un auditeur de bascule (rendu UI). */
    public Runnable listen(Consumer<Boolean> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
