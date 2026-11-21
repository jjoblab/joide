package jo.joide.ai.provider;

/**
 * Conversion de l'effort de raisonnement (Lot 2) : les préférences de JoIDE
 * stockent des identifiants français stables ({@code defaut, aucun, faible,
 * moyen, eleve}) ; les API attendent leurs propres valeurs. {@code defaut}
 * signifie « ne rien envoyer » (le fournisseur choisit). Pur, testé.
 */
public final class Efforts {

    private Efforts() {}

    /** Valeur d'API pour l'effort persisté ; null = ne rien envoyer. */
    public static String toApi(String persisted) {
        if (persisted == null) {
            return null;
        }
        switch (persisted) {
            case "aucun":
                return "none";
            case "faible":
                return "low";
            case "moyen":
                return "medium";
            case "eleve":
                return "high";
            default:
                return null; // "defaut" et toute valeur inconnue : on n'envoie rien
        }
    }
}
