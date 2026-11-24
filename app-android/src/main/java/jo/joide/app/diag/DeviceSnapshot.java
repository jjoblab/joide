package jo.joide.app.diag;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import java.util.Locale;
import jo.joide.diag.DeviceInfo;

/**
 * Photographie <b>non identifiante</b> de l'appareil pour les rapports de
 * plantage (portage CodeIDE §5.8, ADR-021) — toute défaillance retombe sur
 * {@link DeviceInfo#unknown()}, jamais sur un échec du gestionnaire.
 */
public final class DeviceSnapshot {

    private DeviceSnapshot() {
        // Fonction pure — jamais instanciée.
    }

    /**
     * Prend la photographie de l'appareil courant.
     *
     * @param context contexte applicatif
     * @return l'état de l'appareil, ou la valeur inconnue en cas d'échec
     */
    public static DeviceInfo take(Context context) {
        try {
            Runtime runtime = Runtime.getRuntime();
            return new DeviceInfo(
                    nullSafe(Build.MANUFACTURER),
                    nullSafe(Build.MODEL),
                    Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT,
                    Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown",
                    Locale.getDefault().toLanguageTag(),
                    runtime.maxMemory(),
                    runtime.freeMemory(),
                    storageFreeBytes(context));
        } catch (Throwable broken) {
            return DeviceInfo.unknown();
        }
    }

    /** Octets disponibles sous filesDir (0 si la mesure échoue). */
    private static long storageFreeBytes(Context context) {
        try {
            return new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Throwable unreadable) {
            return 0L;
        }
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
