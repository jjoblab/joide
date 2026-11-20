package jo.joide.workbench;

import java.util.function.Consumer;

/** Session d'un build lancé : consomme les événements jusqu'à la fin. */
public interface BuildSession {
    String id();
    void abonnerEvents(Consumer<EvenementBuild> consommateur);
    void annuler();
    boolean estFini();
}
