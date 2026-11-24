package jo.joide.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Moteur du pipeline de journalisation (portage CodeIDE §5.7, ADR-021).
 *
 * <p>Ordre des opérations pour chaque entrée active — le message fourni en
 * fournisseur n'est <b>jamais évalué</b> si le niveau est filtré :</p>
 * <ol>
 *   <li>construction de l'entrée (horodatage et session injectés) ;</li>
 *   <li>expurgation du message et des messages d'exception, puis troncature à
 *       {@link LoggingLimits#MESSAGE_MAX_BYTES} octets ;</li>
 *   <li>aplatissement borné de l'exception (50 tranches, 10 causes) ;</li>
 *   <li>alimentation du tampon circulaire (filons) ;</li>
 *   <li>écriture dans chaque destination qui accepte le niveau ;</li>
 *   <li>notification des auditeurs (abonnés non bloquants : un auditeur lent
 *       ou faillible ne casse jamais le producteur).</li>
 * </ol>
 *
 * <p>Amélioration par rapport au portage d'origine (flot de coroutines) :
 * auditeurs en liste copy-on-write — chaque auditeur reçoit CHAQUE entrée
 * active (pas de perte acceptée côté abonnés), l'interface reste fil-safe
 * sans verrou.</p>
 *
 * @param clock        horloge injectée (tests déterministes)
 * @param configHolder configuration d'exécution, lisible à chaud
 * @param sinks        destinations d'écriture, chacune filtrant par son niveau
 */
public final class LogEngine {

    private final LongSupplier clock;
    private final LogConfigHolder configHolder;
    private final List<LogSink> sinks;
    private final CopyOnWriteArrayList<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();
    private final CircularLogBuffer breadcrumbs =
            new CircularLogBuffer(LoggingLimits.BREADCRUMB_CAPACITY);

    /** Identifiant du lancement courant — présent sur chaque entrée. */
    private final String sessionId = UUID.randomUUID().toString();

    public LogEngine(LongSupplier clock, LogConfigHolder configHolder, List<LogSink> sinks) {
        this.clock = clock;
        this.configHolder = configHolder;
        this.sinks = List.copyOf(sinks);
    }

    /** Identifiant du lancement courant (joint aux rapports de plantage). */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Traite une entrée selon le pipeline décrit en KDoc de classe.
     *
     * @param level     sévérité (null : ignoré silencieusement)
     * @param tag       étiquette du producteur (identifiant, en anglais)
     * @param throwable exception associée éventuelle
     * @param message   fournisseur du message — évalué seulement si l'entrée
     *                  passe le filtre de niveau
     */
    public void log(LogLevel level, String tag, Throwable throwable, Supplier<String> message) {
        LogConfig config = configHolder.read();
        if (level == null || !level.isAtLeast(config.minLevel())) {
            return;
        }
        if (message == null) {
            return; // sans message, il n'y a rien à journaliser
        }
        String text = LogRedactor.redact(message.get());
        LogEntry entry = new LogEntry(clock.getAsLong(), sessionId, level, tag,
                Thread.currentThread().getName(),
                DiagText.truncateUtf8(text, LoggingLimits.MESSAGE_MAX_BYTES),
                throwable == null ? null : flattenAndRedact(throwable));
        breadcrumbs.add(entry);
        for (LogSink sink : sinks) {
            if (level.isAtLeast(sink.minLevel())) {
                try {
                    sink.write(entry);
                } catch (Throwable sinkBroken) {
                    // Une destination fautive ne doit jamais casser le producteur.
                }
            }
        }
        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Throwable listenerBroken) {
                // Un auditeur fautil ne doit jamais casser le producteur.
            }
        }
    }

    /** Raccourci DEBUG (message déjà évalué — utiliser le fournisseur si coûteux). */
    public void d(String tag, String message) {
        log(LogLevel.DEBUG, tag, null, () -> message);
    }

    /** Raccourci DEBUG paresseux — le message n'est évalué que si actif. */
    public void d(String tag, Supplier<String> message) {
        log(LogLevel.DEBUG, tag, null, message);
    }

    /** Raccourci INFO. */
    public void i(String tag, String message) {
        log(LogLevel.INFO, tag, null, () -> message);
    }

    /** Raccourci INFO paresseux. */
    public void i(String tag, Supplier<String> message) {
        log(LogLevel.INFO, tag, null, message);
    }

    /** Raccourci WARN. */
    public void w(String tag, String message) {
        log(LogLevel.WARN, tag, null, () -> message);
    }

    /** Raccourci WARN paresseux. */
    public void w(String tag, Supplier<String> message) {
        log(LogLevel.WARN, tag, null, message);
    }

    /** Raccourci WARN avec exception. */
    public void w(String tag, Throwable throwable, String message) {
        log(LogLevel.WARN, tag, throwable, () -> message);
    }

    /** Raccourci ERROR. */
    public void e(String tag, String message) {
        log(LogLevel.ERROR, tag, null, () -> message);
    }

    /** Raccourci ERROR avec exception — la trace part avec l'entrée. */
    public void e(String tag, Throwable throwable, String message) {
        log(LogLevel.ERROR, tag, throwable, () -> message);
    }

    /** Raccourci ERROR paresseux avec exception. */
    public void e(String tag, Throwable throwable, Supplier<String> message) {
        log(LogLevel.ERROR, tag, throwable, message);
    }

    /**
     * Instantané des dernières entrées du tampon (filons), sans I/O.
     *
     * @param limit taille de la fenêtre demandée (borne à la capacité)
     * @return au plus {@code limit} entrées récentes, la plus récente en dernier
     */
    public List<LogEntry> snapshot(int limit) {
        List<LogEntry> all = breadcrumbs.snapshot();
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /** Efface le tampon de filons (accompagne l'effacement du journal). */
    public void resetBuffer() {
        breadcrumbs.clear();
    }

    /**
     * Ajoute un auditeur — notifié de chaque entrée active, jamais bloquant.
     *
     * @return {@code true} si l'auditeur a été ajouté (idempotent)
     */
    public boolean addListener(Consumer<LogEntry> listener) {
        return listener != null && listeners.addIfAbsent(listener);
    }

    /** Retire un auditeur. */
    public void removeListener(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }

    /**
     * Vidage bloquant borné — délégué aux sinks fichier, réservé au
     * gestionnaire de plantages et aux tests.
     *
     * @param timeoutMillis durée maximale d'attente
     * @return {@code true} si tout ce qui était en attente a été écrit
     */
    public boolean flushBlocking(long timeoutMillis) {
        boolean flushed = true;
        for (LogSink sink : sinks) {
            if (sink instanceof FileSink fileSink) {
                flushed &= fileSink.flushBlocking(timeoutMillis);
            }
        }
        return flushed;
    }

    /** Aplati l'exception aux bornes du journal, puis expurge les messages. */
    private FlattenedException flattenAndRedact(Throwable throwable) {
        return FlattenedException
                .from(throwable, LoggingLimits.EXCEPTION_MAX_FRAMES, LoggingLimits.EXCEPTION_MAX_CAUSES)
                .transformMessages(message -> message == null ? null
                        : DiagText.truncateUtf8(LogRedactor.redact(message),
                                LoggingLimits.MESSAGE_MAX_BYTES));
    }
}
