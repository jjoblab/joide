package jo.joide.ai;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Jeton d'annulation (Lot 1) : bouton Stop → le transport ferme la connexion
 * (fin de l'attente bloquante), la boucle marque le tour annulé, les appels
 * d'outils interrompus reçoivent un résultat « interrompu » pour que la
 * conversation reste envoyable. Pur, thread-safe.
 */
public final class CancellationToken {

    private volatile boolean cancelled;
    private final List<Runnable> onCancel = new CopyOnWriteArrayList<>();

    /** Demande l'annulation : déclenche les actions posées (ex. fermer la connexion). */
    public void cancel() {
        cancelled = true;
        for (Runnable action : onCancel) {
            try {
                action.run();
            } catch (Exception ignored) {
                // Une action de fermeture cassée n'empêche pas l'annulation.
            }
        }
    }

    /** Vrai après {@link #cancel()} — interrogé entre deux lectures du flux. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Pose une action d'annulation (le transport y enregistre la fermeture de
     * sa connexion). L'action s'exécute aussi immédiatement si le jeton est
     * déjà annulé.
     */
    public void onCancel(Runnable action) {
        if (cancelled) {
            action.run();
            return;
        }
        onCancel.add(action);
    }
}
