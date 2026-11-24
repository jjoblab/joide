package jo.joide.diag;

import java.util.List;
import java.util.function.Supplier;

/**
 * Façade publique du journal applicatif (ADR-021) : ce que le reste de
 * JoIDE importe — un point d'entrée statique, initialisé par la coquille
 * Android en première ligne du démarrage, muet (no-op) avant.
 *
 * <p>Avant {@link #init} : chaque appel est silencieusement ignoré —
 * l'application ne peut jamais planter pour avoir journalisé trop tôt.
 * Après : raccourcis d/i/w/e vers le {@link LogEngine}, filtrage par niveau
 * à l'entrée (le message en fournisseur n'est évalué que si l'entrée
 * passe).</p>
 *
 * <p>Le code qui veut rester testable sans façade globale prend un
 * {@link LogEngine} en paramètre ; la façade n'est là que pour les points
 * d'appel dispersés (activités, fragments) qui n'ont pas de constructeur.</p>
 */
public final class AppLog {

    private static volatile LogEngine engine;

    private AppLog() {
        // Façade statique — jamais instanciée.
    }

    /**
     * Branche le moteur — appelé une fois par la coquille au démarrage ;
     * les appels suivants sont ignorés (premier moteur gagnant, jamais de
     * remplacement à chaud de la façade).
     */
    public static void init(LogEngine moteur) {
        if (engine == null && moteur != null) {
            engine = moteur;
        }
    }

    /** Le journal est-il actif dans ce processus ? */
    public static boolean isInitialized() {
        return engine != null;
    }

    /** Moteur branché (null avant l'initialisation) — pour le gestionnaire de plantage. */
    public static LogEngine engine() {
        return engine;
    }

    /** Journalise au niveau DEBUG (message simple). */
    public static void d(String tag, String message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.d(tag, message);
        }
    }

    /** Journalise au niveau DEBUG (message paresseux). */
    public static void d(String tag, Supplier<String> message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.d(tag, message);
        }
    }

    /** Journalise au niveau INFO. */
    public static void i(String tag, String message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.i(tag, message);
        }
    }

    /** Journalise au niveau INFO (message paresseux). */
    public static void i(String tag, Supplier<String> message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.i(tag, message);
        }
    }

    /** Journalise au niveau WARN. */
    public static void w(String tag, String message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.w(tag, message);
        }
    }

    /** Journalise au niveau WARN avec exception. */
    public static void w(String tag, Throwable throwable, String message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.w(tag, throwable, message);
        }
    }

    /** Journalise au niveau ERROR. */
    public static void e(String tag, String message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.e(tag, message);
        }
    }

    /** Journalise au niveau ERROR avec exception (la trace part avec l'entrée). */
    public static void e(String tag, Throwable throwable, String message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.e(tag, throwable, message);
        }
    }

    /** Journalise au niveau ERROR (message paresseux) avec exception. */
    public static void e(String tag, Throwable throwable, Supplier<String> message) {
        LogEngine moteur = engine;
        if (moteur != null) {
            moteur.e(tag, throwable, message);
        }
    }

    /**
     * Instantané des {@code limit} dernières entrées (filons), sans I/O —
     * vide si le journal n'est pas initialisé.
     */
    public static List<LogEntry> snapshot(int limit) {
        LogEngine moteur = engine;
        return moteur == null ? List.of() : moteur.snapshot(limit);
    }

    /**
     * Vidage bloquant borné du journal — réservé au gestionnaire de plantage.
     *
     * @param timeoutMillis durée maximale d'attente
     * @return {@code true} si tout est parti (faux si journal absent)
     */
    public static boolean flushBlocking(long timeoutMillis) {
        LogEngine moteur = engine;
        return moteur != null && moteur.flushBlocking(timeoutMillis);
    }
}
