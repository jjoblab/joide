package jo.joide.ai;

/**
 * Temps relatif d'affichage (Lot 0) : le calcul des durées (« à l'instant »,
 * minutes, heures, jours) est pur et testé sur JVM ; la coquille formate
 * avec ses ressources de chaînes (jamais de texte fabriqué côté modèle).
 */
public final class RelativeTimes {

    private RelativeTimes() {}

    /** Unité de l'âge affiché. */
    public enum Unit { MINUTES, HOURS, DAYS }

    /** Âge calculé (unité + valeur) ; la coquille choisit le libellé. */
    public record Ago(Unit unit, long value) {}

    /**
     * Âge d'un instant par rapport à « maintenant » : minutes sous une heure,
     * heures sous un jour, jours au-delà. Une durée nulle ou négative
     * (horloge ajustée) rend 0 minute — jamais de valeur négative affichée.
     */
    public static Ago agoOf(long then, long now) {
        long minutes = Math.max(0, (now - then) / 60_000L);
        if (minutes < 60) {
            return new Ago(Unit.MINUTES, minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return new Ago(Unit.HOURS, hours);
        }
        return new Ago(Unit.DAYS, hours / 24);
    }
}
