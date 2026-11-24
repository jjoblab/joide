package jo.joide.app.diag;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import androidx.annotation.RequiresApi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import jo.joide.app.BuildConfig;
import jo.joide.diag.CrashAppInfo;
import jo.joide.diag.CrashReportFactory;
import jo.joide.diag.CrashReportStore;
import jo.joide.diag.CrashType;

/**
 * Détection au démarrage des sorties non traitées (portage CodeIDE §5.8,
 * ADR-021) : ANR et plantages natifs du lancement précédent, lus dans
 * {@code ApplicationExitInfo} (API 30+). Appelé hors fil principal par
 * {@link DiagApp}.
 *
 * <p>Les doublons sont impossibles : le dernier horodatage traité vit dans
 * un fichier témoin du répertoire des rapports. Seul le processus principal
 * est examiné. Les rapports ANR/natifs sont des <b>reconstructions</b> :
 * session, écran et filons de la session morte sont perdus — le rapport le
 * dit, il n'invente rien.</p>
 */
public final class ExitInfoRecorder {

    private static final String MARKER_FILE = "exit-info-marker.txt";
    private static final int BUFFER_SIZE = 4 * 1024;

    private ExitInfoRecorder() {
        // Fonctions pures — jamais instanciée.
    }

    /**
     * Enregistre les sorties non traitées du lancement précédent.
     *
     * @param context     contexte applicatif
     * @param reportStore persistance des rapports (répertoire partagé)
     * @return le nombre de rapports créés
     */
    @RequiresApi(Build.VERSION_CODES.R)
    public static int recordPending(Context context, CrashReportStore reportStore) {
        return processExits(context, reportStore);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static int processExits(Context context, CrashReportStore reportStore) {
        ActivityManager activityManager =
                context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return 0;
        }
        java.util.List<ApplicationExitInfo> exits;
        try {
            exits = activityManager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, jo.joide.diag.CrashLimits.EXIT_INFO_MAX);
        } catch (RuntimeException serviceBroken) {
            // Service indisponible : rien à traiter, aucune erreur.
            return 0;
        }

        long lastProcessed = readMarker(context);
        long maximum = lastProcessed;
        int created = 0;

        for (ApplicationExitInfo exit : exits) {
            if (exit.getTimestamp() > maximum) {
                maximum = exit.getTimestamp();
            }
            if (exit.getTimestamp() <= lastProcessed) {
                continue;
            }
            if (record(context, reportStore, exit)) {
                created++;
            }
        }

        if (maximum > lastProcessed) {
            writeMarker(context, maximum);
        }
        return created;
    }

    /** Enregistre une sortie si elle porte un type de rapport (ANR, natif). */
    @RequiresApi(Build.VERSION_CODES.R)
    private static boolean record(Context context, CrashReportStore reportStore,
            ApplicationExitInfo exit) {
        CrashType type = typeOf(exit.getReason());
        if (type == null) {
            return false;
        }
        String processName = exit.getProcessName();
        if (processName != null && !processName.equals(context.getPackageName())) {
            return false;
        }
        try {
            CrashReportFactory factory = new CrashReportFactory(System::currentTimeMillis);
            reportStore.save(factory.fromExitInfo(
                    type,
                    exit.getTimestamp(),
                    reasonLabel(exit),
                    readBoundedTrace(exit),
                    appInfo(context),
                    () -> DeviceSnapshot.take(context)));
            return true;
        } catch (IOException writeFailed) {
            // Rapport non écrit : le marqueur avance quand même (traité) pour
            // ne pas tourner en rond.
            return false;
        }
    }

    /** Type de rapport pour cette cause de sortie, ou null si ignorée. */
    @RequiresApi(Build.VERSION_CODES.R)
    private static CrashType typeOf(int reason) {
        if (reason == ApplicationExitInfo.REASON_ANR) {
            return CrashType.ANR;
        }
        if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            return CrashType.NATIVE;
        }
        return null;
    }

    /** Libellé lisible de la cause système. */
    @RequiresApi(Build.VERSION_CODES.R)
    private static String reasonLabel(ApplicationExitInfo exit) {
        String label;
        if (exit.getReason() == ApplicationExitInfo.REASON_ANR) {
            label = "ANR";
        } else if (exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            label = "plantage natif";
        } else {
            label = "raison " + exit.getReason();
        }
        StringBuilder text = new StringBuilder("Sortie de processus (").append(label).append(')');
        if (exit.getDescription() != null && !exit.getDescription().isBlank()) {
            text.append(" — ").append(exit.getDescription());
        }
        return text.toString();
    }

    /** Lit la trace en la bornant — texte pur, jamais une exception. */
    @RequiresApi(Build.VERSION_CODES.R)
    private static String readBoundedTrace(ApplicationExitInfo exit) {
        InputStream stream = null;
        try {
            stream = exit.getTraceInputStream();
            if (stream == null) {
                return null;
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            while (total < jo.joide.diag.CrashLimits.EXIT_INFO_TRACE_MAX_BYTES) {
                int asked = (int) Math.min(buffer.length,
                        jo.joide.diag.CrashLimits.EXIT_INFO_TRACE_MAX_BYTES - total);
                int read = stream.read(buffer, 0, asked);
                if (read <= 0) {
                    break;
                }
                result.write(buffer, 0, read);
                total += read;
            }
            return result.toString("UTF-8");
        } catch (IOException unreadable) {
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException closeFailed) {
                    // Fermeture au mieux.
                }
            }
        }
    }

    private static CrashAppInfo appInfo(Context context) {
        return new CrashAppInfo(
                BuildConfig.VERSION_NAME == null ? "?" : BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.DEBUG ? "debug" : "release",
                context.getPackageName());
    }

    private static java.io.File marker(Context context) {
        return new java.io.File(context.getFilesDir(),
                jo.joide.diag.CrashLimits.DIRECTORY_NAME + "/" + MARKER_FILE);
    }

    private static long readMarker(Context context) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(
                    marker(context).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return Long.parseLong(text.trim());
        } catch (Exception unreadable) {
            return 0L;
        }
    }

    private static void writeMarker(Context context, long value) {
        try {
            java.io.File marker = marker(context);
            marker.getParentFile().mkdirs();
            java.nio.file.Files.write(marker.toPath(),
                    Long.toString(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException writeFailed) {
            // Marqueur perdu : au pire, une sortie déjà traitée sera
            // re-scannée — un doublon de rapport, jamais une boucle.
        }
    }
}
