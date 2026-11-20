package jo.joide.workbench;

import java.util.function.Consumer;

/** Session d'un build lancé : consomme les événements jusqu'à la fin. */
public interface BuildSession {

    String id();

    /** Abonne un consommateur aux événements du build (fil d'attente bornée côté implémentation). */
    void subscribe(Consumer<BuildEvent> consumer);

    /** Annule le build en cours (best effort ; un {@code Finished} suit toujours). */
    void cancel();

    boolean isFinished();
}
