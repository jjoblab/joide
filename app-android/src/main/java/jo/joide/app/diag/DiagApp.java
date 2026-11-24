package jo.joide.app.diag;

import android.app.Application;
import android.os.Build;
import jo.joide.app.BuildConfig;
import jo.joide.diag.AppLog;
import jo.joide.diag.CrashHandler;
import jo.joide.diag.CrashLoopDetector;
import jo.joide.diag.CrashReportStore;
import jo.joide.diag.DeviceInfo;
import jo.joide.diag.FileSink;
import jo.joide.diag.JsonlLogStore;
import jo.joide.diag.LogConfig;
import jo.joide.diag.LogConfigHolder;
import jo.joide.diag.LogEngine;
import jo.joide.diag.LogLevel;
import jo.joide.diag.LoggingLimits;

/**
 * Application JoIDE (ADR-021) : point d'entrée UNIQUE de l'initialisation du
 * diagnostic — installée en première ligne du démarrage, AVANT toute
 * activité.
 *
 * <p>Ordre exact (portage CodeIDE §5.8, règle « avant toute
 * initialisation ») :</p>
 * <ol>
 *   <li>gestionnaire de plantages installé (sa construction ne fait aucune
 *       I/O) — un plantage dès les initialiseurs statiques est capturé ;</li>
 *   <li>pipeline de journalisation démarré (sink logcat + sink fichier JSONL,
 *       niveau initial lu des préférences — persisté, effet immédiat via
 *       {@link LogConfigHolder}) ;</li>
 *   <li>liaison journal ↔ gestionnaire (session, filons, vidage borné) ;</li>
 *   <li>lecture des sorties du processus PRÉCÉDENT (ApplicationExitInfo,
 *       API 30+) sur un fil de fond — ANR et plantages natifs deviennent des
 *       rapports au démarrage suivant ;</li>
 *   <li>entrée INFO « démarrage » — le journal commence à parler.</li>
 * </ol>
 *
 * <p>Chaque brique est indépendamment protégée : le diagnostic ne doit jamais
 * empêcher l'IDE de démarrer.</p>
 */
public final class DiagApp extends Application {

    /** Préférences du diagnostic (niveau de journalisation persisté, ADR-021). */
    static final String PREFS = "joide_diag";

    private static final String KEY_LEVEL = "level";

    /** Détenteur de configuration partagé avec les écrans (bascule à chaud). */
    private static volatile LogConfigHolder configHolder;

    @Override
    public void onCreate() {
        super.onCreate();
        // 1. Gestionnaire de plantages : AVANT TOUT — rapport complet (trace,
        //    filons, appareil, boucle), écriture synchrone bornée, puis
        //    délégation au système (le dialogue natif reste, le rapport vit).
        CrashReportStore reportStore = new CrashReportStore(
                getFilesDir().toPath().resolve(jo.joide.diag.CrashLimits.DIRECTORY_NAME));
        CrashLoopDetector loopDetector = new CrashLoopDetector(
                getFilesDir().toPath().resolve(jo.joide.diag.CrashLimits.DIRECTORY_NAME));
        LastScreenTracker screenTracker = new LastScreenTracker();
        registerActivityLifecycleCallbacks(screenTracker);
        CrashHandler crashHandler = new CrashHandler(reportStore, loopDetector,
                System::currentTimeMillis,
                () -> new jo.joide.diag.CrashAppInfo(
                        BuildConfig.VERSION_NAME == null ? "?" : BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        BuildConfig.DEBUG ? "debug" : "release",
                        getPackageName()),
                Thread.getDefaultUncaughtExceptionHandler());
        crashHandler.bindDevice(() -> DeviceSnapshot.take(this));
        crashHandler.bindLastScreen(screenTracker::current);

        // 2. Pipeline de journalisation : logcat + fichier JSONL borné.
        JsonlLogStore logStore = new JsonlLogStore(
                getFilesDir().toPath().resolve(LoggingLimits.LOGS_DIR_NAME));
        configHolder = new LogConfigHolder(new LogConfig(initialLevel(), true,
                LoggingLimits.RETENTION_DAYS, LoggingLimits.MAX_ARCHIVE_FILES,
                LoggingLimits.MAX_FILE_BYTES));
        FileSink fileSink = new FileSink(logStore, System::currentTimeMillis,
                configHolder::read, failure -> android.util.Log
                        .w("JoIDE.Diag", "écriture du journal échouée", failure));
        fileSink.start();
        LogEngine engine = new LogEngine(System::currentTimeMillis, configHolder,
                java.util.List.of(new LogcatSink(), fileSink));
        AppLog.init(engine);

        // 3. Liaison journal ↔ gestionnaire : filons et vidage borné au plantage.
        crashHandler.bindLogging(engine::sessionId,
                () -> engine.snapshot(LoggingLimits.MAX_BREADCRUMBS),
                engine::flushBlocking);
        Thread.setDefaultUncaughtExceptionHandler(crashHandler);

        // 4. Sorties du processus précédent (ANR, plantage natif) : rapports.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new Thread(() -> {
                try {
                    int crees = ExitInfoRecorder.recordPending(this, reportStore);
                    if (crees > 0) {
                        AppLog.w("Crash", crees
                                + " sortie(s) du lancement précédent converties en rapports (ANR/natif)");
                    }
                } catch (Throwable exitInfoFailure) {
                    // Bonus : son échec ne doit jamais toucher le démarrage.
                }
            }, "joide-diag-exitinfo").start();
        }

        // 5. Le journal commence : la session s'identifie.
        AppLog.i("App", "JoIDE démarré (version " + BuildConfig.VERSION_NAME
                + ", Android " + Build.VERSION.RELEASE + ")");
    }

    /**
     * Détenteur de configuration du journal (bascule de niveau à chaud depuis
     * l'écran Diagnostic) — null avant le démarrage, jamais après.
     */
    public static LogConfigHolder configHolder() {
        return configHolder;
    }

    /**
     * Niveau initial : celui mémorisé dans les préférences (ADR-021 — le
     * réglage survit aux redémarrages), sinon debug → DEBUG, release → INFO.
     */
    private LogLevel initialLevel() {
        try {
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LEVEL, null);
            if (saved != null) {
                return LogLevel.valueOf(saved);
            }
        } catch (Throwable unreadable) {
            // Valeur pourrie : défaut du type de build.
        }
        return BuildConfig.DEBUG ? LogLevel.DEBUG : LogLevel.INFO;
    }

    /**
     * Change le niveau minimal du journal : persisté + effet immédiat.
     *
     * @param level nouveau niveau (null ignoré)
     */
    public static void setLevel(android.content.Context context, LogLevel level) {
        if (level == null) {
            return;
        }
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_LEVEL, level.name()).apply();
        LogConfigHolder holder = configHolder;
        if (holder != null) {
            LogConfig current = holder.read();
            holder.update(new LogConfig(level, current.fileLoggingEnabled(),
                    current.retentionDays(), current.maxArchiveFiles(), current.maxFileBytes()));
        }
    }
}
