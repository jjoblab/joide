package jo.joide.diag;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Gestionnaire des plantages du processus (portage CodeIDE §5.8, ADR-021),
 * <b>JVM pur</b> : la coquille Android le construit et l'installe en toute
 * première ligne du démarrage — sa construction ne fait aucune I/O.
 *
 * <p>Enchaînement exigé, tout dans un try/catch global avec garde de
 * ré-entrance :</p>
 * <ol>
 *   <li>détection de boucle (historique persistant, ≥ 3 plantages en 60 s) ;</li>
 *   <li>journalisation de l'entrée ERROR dans le pipeline (amélioration
 *       JoIDE : la trace du plantage vit AUSSI dans le journal, elle part
 *       avec le vidage borné) ;</li>
 *   <li>construction du rapport (exception expurgée et bornée, filons,
 *       dernier écran, durée du processus, indicateur de boucle) ;</li>
 *   <li>écriture <b>synchrone et atomique</b> du rapport ;</li>
 *   <li>vidage borné du journal (≤ 500 ms, jamais au-delà du budget total) ;</li>
 *   <li>délégation au gestionnaire précédent — le système montre son dialogue
 *       de plantage, le processus meurt normalement (contrairement à CodeIDE
 *       qui relance un écran dédié : JoIDE est monoprocessus, la suite se
 *       joue au démarrage suivant — l'écran Diagnostic signale les rapports
 *       non consultés).</li>
 * </ol>
 *
 * <p>La durée totale reste bornée (≤ 2 s) : le vidage est borné par ce qui
 * reste du budget. Le gestionnaire ne doit jamais lui-même planter : tout
 * échec interne délègue immédiatement.</p>
 *
 * <p>Les fournisseurs (session, filons, appareil, écran) sont branchés
 * <b>après</b> l'installation — des {@code volatile} : un plantage avant le
 * branchement produit un rapport sans filons, dégradation acceptée, jamais
 * un échec.</p>
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final long NANOS_PER_MILLIS = 1_000_000L;

    private final CrashReportStore reportStore;
    private final CrashLoopDetector loopDetector;
    private final LongSupplier clock;
    private final Thread.UncaughtExceptionHandler previous;
    private final CrashReportFactory factory;

    /** Fournisseurs branchés après l'installation (volatile : sans verrou). */
    private volatile Supplier<String> sessionId = () -> "";
    private volatile Supplier<List<LogEntry>> breadcrumbs = List::of;
    private volatile java.util.function.LongConsumer flush = timeout -> { };
    private volatile Supplier<DeviceInfo> deviceInfo = DeviceInfo::unknown;
    private volatile Supplier<CrashAppInfo> appInfo;
    private volatile Supplier<String> lastScreen = () -> null;

    /** Garde de ré-entrance : un second plantage pendant la gestion délègue. */
    private final AtomicBoolean guard = new AtomicBoolean(false);

    /** Instant de l'installation — durée du processus dans les rapports. */
    private final long processStartNanos = System.nanoTime();

    /**
     * @param reportStore  persistance des rapports (répertoire filesDir/crashes)
     * @param loopDetector détection de boucle
     * @param clock        horloge (horodatage du rapport)
     * @param appInfo      identité du build (depuis BuildConfig côté coquille)
     * @param previous     gestionnaire précédent, auquel on délègue
     */
    public CrashHandler(CrashReportStore reportStore, CrashLoopDetector loopDetector,
            LongSupplier clock, Supplier<CrashAppInfo> appInfo,
            Thread.UncaughtExceptionHandler previous) {
        this.reportStore = reportStore;
        this.loopDetector = loopDetector;
        this.clock = clock;
        this.appInfo = appInfo;
        this.previous = previous;
        this.factory = new CrashReportFactory(clock);
    }

    /**
     * Branche la liaison avec le journal (fournisseurs posés par la coquille
     * juste après l'installation) : identifiant de session, filons et vidage
     * borné. Jusqu'à l'appel, un plantage produit un rapport sans filons ni
     * vidage — dégradation acceptée, jamais un échec.
     */
    public void bindLogging(Supplier<String> sessionId, Supplier<List<LogEntry>> breadcrumbs,
            java.util.function.LongConsumer flush) {
        if (sessionId != null) {
            this.sessionId = sessionId;
        }
        if (breadcrumbs != null) {
            this.breadcrumbs = breadcrumbs;
        }
        if (flush != null) {
            this.flush = flush;
        }
    }

    /**
     * Branche le fournisseur de photographie d'appareil (coquille Android).
     */
    public void bindDevice(Supplier<DeviceInfo> deviceInfo) {
        if (deviceInfo != null) {
            this.deviceInfo = deviceInfo;
        }
    }

    /**
     * Branche le fournisseur du dernier écran connu (pisteur de cycles de
     * vie côté coquille, ou destination de navigation).
     */
    public void bindLastScreen(Supplier<String> lastScreen) {
        if (lastScreen != null) {
            this.lastScreen = lastScreen;
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        if (!guard.compareAndSet(false, true)) {
            delegate(thread, throwable);
            return;
        }
        try {
            long startNanos = System.nanoTime();

            boolean crashLoop = loopDetector.recordAndDetect(clock.getAsLong());

            // Amélioration JoIDE : la trace part AUSSI dans le journal — le
            // vidage borné l'emporte vers le disque, et un rapport de plantage
            // ultérieur la retrouve dans ses filons si le fil survit.
            try {
                LogEngine engine = AppLog.engine();
                if (engine != null) {
                    engine.e("Crash", throwable,
                            () -> "Exception non interceptée dans le fil « "
                                    + thread.getName() + " »");
                }
            } catch (Throwable logFailure) {
                // Journalier en panne : le rapport reste la source de vérité.
            }

            CrashReport report = factory.create(throwable, thread.getName(),
                    safeAppInfo(), deviceInfo, sessionId.get(), breadcrumbs.get(),
                    lastScreen.get(), processUptimeMillis(), crashLoop);
            reportStore.save(report);
            flushJournal(startNanos);

            // JoIDE est monoprocessus : pas d'écran dédié de plantage (CodeIDE
            // en relance un dans un processus séparé) — délégation au système
            // dans TOUS les cas, l'écran Diagnostic signale le rapport au
            // démarrage suivant. En boucle, le comportement est identique —
            // c'est le système qui casse la spirale.
            delegate(thread, throwable);
        } catch (Throwable handlerFailure) {
            // Le gestionnaire ne doit jamais lui-même planter : délégation.
            delegate(thread, throwable);
        }
    }

    /** Vidage borné du journal : le reste du budget, plafonné à 500 ms. */
    private void flushJournal(long startNanos) {
        long elapsedMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLIS;
        long remaining = CrashLimits.HANDLER_BUDGET_MILLIS - elapsedMillis;
        long timeout = Math.max(0L, Math.min(remaining, CrashLimits.FLUSH_TIMEOUT_MILLIS));
        flush.accept(timeout);
    }

    /** Durée écoulée depuis l'installation (proche du démarrage). */
    private long processUptimeMillis() {
        return (System.nanoTime() - processStartNanos) / NANOS_PER_MILLIS;
    }

    private CrashAppInfo safeAppInfo() {
        try {
            return appInfo.get();
        } catch (Throwable broken) {
            return new CrashAppInfo("unknown", 0L, "unknown", "unknown");
        }
    }

    private void delegate(Thread thread, Throwable throwable) {
        if (previous != null) {
            try {
                previous.uncaughtException(thread, throwable);
            } catch (Throwable delegationBroken) {
                // Le gestionnaire précédent est lui-même en panne : le système
                // reprend la main de toute façon (le fil meurt).
            }
        }
    }
}
