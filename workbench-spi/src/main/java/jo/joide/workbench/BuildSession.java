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

    /**
     * Le MOTEUR compose-t-il lui-même la console (Forge v0.2.26+, protocole 4) ?
     * Vrai : les {@code Log} reçus sont déjà des lignes rédigées et TRIÉES par le
     * moteur (lignes de tâches, verdict — niveau {@code LIFECYCLE}) ; l'atelier
     * les AFFICHE et ne compose plus ses propres lignes de tâches ni de verdict
     * (rien ne double). Faux (moteur plus ancien, autre backend) : la composition
     * locale d'avant reste la règle.
     *
     * @return vrai si la console appartient au moteur.
     */
    default boolean engineConsole() {
        return false;
    }
}
