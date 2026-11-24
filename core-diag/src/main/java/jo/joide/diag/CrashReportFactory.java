package jo.joide.diag;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Fabrique des {@link CrashReport} (portage CodeIDE §5.8, ADR-021) : capture
 * une exception, borne chaque section et <b>expurge les messages</b> avant
 * toute persistance. La construction ne fait aucune I/O et ne peut pas
 * échouer : la photographie de l'appareil elle-même est protégée (valeur de
 * repli fournie par l'appelant).
 */
public final class CrashReportFactory {

    private final LongSupplier clock;

    /**
     * @param clock horloge injectée (horodatage déterministe en test)
     */
    public CrashReportFactory(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Construit le rapport d'une exception non interceptée.
     *
     * @param throwable      exception à photographier
     * @param threadName     nom du fil qui a planté
     * @param appInfo        identité du build
     * @param deviceInfo     fournisseur de la photographie de l'appareil (repli
     *                       sûr en cas d'échec)
     * @param sessionId      identifiant de session de journalisation (peut être vide)
     * @param breadcrumbs    filons de pain (les {@link LoggingLimits#MAX_BREADCRUMBS}
     *                       derniers sont gardés)
     * @param lastScreen     dernier écran connu, ou {@code null}
     * @param processUptimeMs durée depuis le démarrage du processus
     * @param isCrashLoop    indicateur de boucle de plantages
     * @return le rapport complet, borné et expurgé
     */
    public CrashReport create(Throwable throwable, String threadName, CrashAppInfo appInfo,
            Supplier<DeviceInfo> deviceInfo, String sessionId, List<LogEntry> breadcrumbs,
            String lastScreen, long processUptimeMs, boolean isCrashLoop) {
        List<LogEntry> crumbs = breadcrumbs == null ? List.of() : breadcrumbs;
        List<LogEntry> kept = crumbs.size() > LoggingLimits.MAX_BREADCRUMBS
                ? crumbs.subList(crumbs.size() - LoggingLimits.MAX_BREADCRUMBS,
                        crumbs.size())
                : crumbs;
        return new CrashReport(UUID.randomUUID().toString(),
                CrashType.EXCEPTION,
                clock.getAsLong(),
                sessionId == null ? "" : sessionId,
                appInfo,
                safeDevice(deviceInfo),
                threadName == null ? "" : threadName,
                flattenAndRedact(throwable),
                List.copyOf(kept),
                lastScreen,
                Math.max(0L, processUptimeMs),
                isCrashLoop);
    }

    /**
     * Construit le rapport d'une sortie de processus détectée au démarrage
     * (ANR ou plantage natif, API 30+ côté coquille).
     *
     * <p>Les filons, l'écran et la session de la session morte sont perdus —
     * le rapport reconstruit reste honnête : chaîne vide, écran inconnu.</p>
     *
     * @param type             ANR ou plantage natif
     * @param timestampMillis  horodatage de la sortie (fourni par le système)
     * @param reason           libellé de la cause système
     * @param trace            trace tronquée, si disponible (null sinon)
     * @param appInfo          identité du build courant
     * @param deviceInfo       fournisseur de la photographie de l'appareil
     * @return le rapport reconstruit
     */
    public CrashReport fromExitInfo(CrashType type, long timestampMillis, String reason,
            String trace, CrashAppInfo appInfo, Supplier<DeviceInfo> deviceInfo) {
        List<String> frames = new java.util.ArrayList<>();
        if (trace != null) {
            for (String line : trace.split("\n", -1)) {
                if (line.isBlank() || frames.size() >= CrashLimits.MAX_FRAMES) {
                    continue;
                }
                frames.add(line.strip());
            }
        }
        return new CrashReport(UUID.randomUUID().toString(),
                type,
                timestampMillis,
                "",
                appInfo,
                safeDevice(deviceInfo),
                "",
                new FlattenedException(type.name(),
                        redactAndTruncate(reason, CrashLimits.MAX_MESSAGE_BYTES),
                        frames, null, List.of()),
                List.of(),
                null,
                0L,
                false);
    }

    /** Photographie de l'appareil protégée : l'échec rend « unknown », jamais une exception. */
    private static DeviceInfo safeDevice(Supplier<DeviceInfo> deviceInfo) {
        try {
            DeviceInfo info = deviceInfo == null ? null : deviceInfo.get();
            return info == null ? DeviceInfo.unknown() : info;
        } catch (Throwable broken) {
            return DeviceInfo.unknown();
        }
    }

    /** Aplati l'exception aux bornes des rapports, puis expurge et tronque. */
    private static FlattenedException flattenAndRedact(Throwable throwable) {
        return FlattenedException
                .from(throwable, CrashLimits.MAX_FRAMES, CrashLimits.MAX_CAUSES)
                .transformMessages(message -> message == null ? null
                        : redactAndTruncate(message, CrashLimits.MAX_MESSAGE_BYTES));
    }

    /** Expurge (règle 15) puis tronque sans couper un caractère multi-octets. */
    private static String redactAndTruncate(String message, int limit) {
        return DiagText.truncateUtf8(LogRedactor.redact(message), limit);
    }
}
