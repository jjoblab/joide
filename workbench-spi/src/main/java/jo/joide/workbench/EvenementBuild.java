package jo.joide.workbench;

/** Événement d'un build, commun aux deux modes (conception §4). */
public sealed interface EvenementBuild permits
        EvenementBuild.Journal, EvenementBuild.EtatTache, EvenementBuild.Progression,
        EvenementBuild.DiagnosticRecu, EvenementBuild.Fin {

    /** Entrée de journal (section Console). */
    record Journal(long horodatage, Niveau niveau, String message) implements EvenementBuild {
        /** Niveau de journal. */
        public enum Niveau { DEBUG, INFO, AVERTISSEMENT, ERREUR }
    }

    /** Changement d'état d'une tâche du graphe (section Console). */
    record EtatTache(String tache, Etat etat, long dureeMs) implements EvenementBuild {
        /** État d'une tâche. */
        public enum Etat { DEMARREE, REUSSIE, ECHOUEE, SAUTEE, A_JOUR }
    }

    /** Progression globale (barre de l'atelier). */
    record Progression(int tachesTotal, int tachesTerminees) implements EvenementBuild {}

    /** Diagnostic structuré (section Problèmes + saut éditeur). */
    record DiagnosticRecu(Diagnostic diagnostic) implements EvenementBuild {}

    /** Fin de build : toujours le dernier événement. */
    record Fin(boolean succes, String tacheFautive, long dureeMs, String message) implements EvenementBuild {}
}
