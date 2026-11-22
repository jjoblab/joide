package jo.joide.workbench;

import java.util.Locale;

/**
 * Durées de build (tâches, run complet) : formatage court et lisible,
 * « 0,9 s · 2,4 s · 18 s · 1 min 12 s · 1 h 04 min ». Pur et testé sur
 * JVM ; la coquille ne formate rien elle-même (une seule règle, partout).
 */
public final class Durations {

    private Durations() {}

    /**
     * Format court : sous 10 s une décime arrondie (« 2,4 s », séparateur de
     * la locale), sous une minute des secondes entières, puis minutes+secondes,
     * puis heures+minutes. Une durée nulle ou négative est « 0 s » (jamais
     * de valeur négative affichée).
     */
    public static String format(long millis, Locale locale) {
        long seconds = Math.round(Math.max(0, millis) / 1000.0);
        if (seconds < 10) {
            long decis = Math.round(Math.max(0, millis) / 100.0);
            return String.format(locale, "%.1f s", decis / 10.0);
        }
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) {
            return rest == 0
                    ? minutes + " min"
                    : minutes + " min " + String.format(locale, "%02d", rest) + " s";
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        return restMinutes == 0
                ? hours + " h"
                : hours + " h " + String.format(locale, "%02d", restMinutes) + " min";
    }
}
