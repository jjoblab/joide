package jo.joide.ai;

/**
 * Porte de permission unique de l'agent (conception §8, REF-117) : le **seul**
 * point de décision pour tout outil mutant. Trois modes persistés ; le refus
 * renvoie au modèle un résultat d'erreur **informatif** (pas une exception
 * muette) ; la fermeture du dialogue d'approbation vaut refus (défaut sûr).
 *
 * <p>L'approbation porte le diff avant/après quand l'outil peut le fournir
 * ({@code preview} non nul) — le diff vit DANS l'invite, pas après coup.</p>
 */
public interface PermissionGate {

    /** Modes persistés (réglages IA, REF-117). */
    enum Mode {
        /** Demander à chaque mutation (diff dans l'invite). */
        ASK_EACH,
        /** Accepter automatiquement (régler avec soin). */
        AUTO_ACCEPT,
        /** Plan seulement : toute mutation est refusée avec message informatif. */
        PLAN_ONLY
    }

    /** Demande d'autorisation d'une mutation (outil, chemin, aperçu). */
    record PermissionRequest(String tool, String path, String preview) {}

    /** Mode courant de la porte (moitié volatile du prompt — REF-111). */
    Mode mode();

    /**
     * Autorise (ou refuse) une mutation. Appelé par la boucle pour chaque outil
     * mutant, dans l'ordre strict des appels (jamais en course).
     *
     * @return vrai = autorisé ; faux = refusé (le motif informatif est rendu
     *         au modèle par la boucle).
     */
    boolean authorize(PermissionRequest request);

    /**
     * Implémentation de test : décision fixée d'avance (mode ou réponses
     * scriptées), aucune UI — la porte reste unique et testable sur JVM.
     */
    final class Scripted implements PermissionGate {

        private final Mode mode;
        private final java.util.List<PermissionRequest> seen = new java.util.ArrayList<>();

        public Scripted(Mode mode) {
            this.mode = mode;
        }

        @Override
        public Mode mode() {
            return mode;
        }

        @Override
        public boolean authorize(PermissionRequest request) {
            seen.add(request);
            return switch (mode) {
                case ASK_EACH -> false; // le test scripte le refus par défaut
                case AUTO_ACCEPT -> true;
                case PLAN_ONLY -> false;
            };
        }

        /** Demandes passées par la porte (assertions des tests). */
        public java.util.List<PermissionRequest> seen() {
            return java.util.List.copyOf(seen);
        }
    }
}
