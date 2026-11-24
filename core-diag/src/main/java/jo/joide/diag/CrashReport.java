package jo.joide.diag;

import java.util.List;

/**
 * Rapport de plantage complet (portage CodeIDE §5.8, ADR-021).
 *
 * <p>Instantané autonome capturé au pire moment : tout ce qui aide à
 * comprendre un défaut sans service réseau. Le contenu est <b>déjà expurgé
 * et borné à la construction</b> — sûr à écrire sur disque, à afficher et à
 * exporter.</p>
 *
 * @param id identifiant unique (UUID) du rapport
 * @param type nature du plantage
 * @param timestampMillis horodatage du plantage, en millisecondes epoch
 * @param sessionId identifiant de la session de journalisation, ou chaîne
 *        vide quand il est inconnu (rapports ANR/natifs reconstruits)
 * @param application identité du build
 * @param device photographie non identifiante de l'appareil
 * @param threadName nom du fil qui a planté, ou chaîne vide si inconnu
 * @param exception chaîne d'exceptions aplatie, expurgée et bornée
 * @param breadcrumbs dernières entrées de journal (au plus 50), contexte
 *        menant au plantage
 * @param lastScreen dernier écran connu, ou {@code null} avant tout affichage
 * @param processUptimeMs durée écoulée depuis le démarrage du processus
 * @param isCrashLoop {@code true} quand ce plantage fait partie d'une boucle
 *        (au moins 3 plantages en 60 s) — l'écran de diagnostic l'annonce
 */
public record CrashReport(
        String id,
        CrashType type,
        long timestampMillis,
        String sessionId,
        CrashAppInfo application,
        DeviceInfo device,
        String threadName,
        FlattenedException exception,
        List<LogEntry> breadcrumbs,
        String lastScreen,
        long processUptimeMs,
        boolean isCrashLoop) {

    public CrashReport {
        breadcrumbs = breadcrumbs == null ? List.of() : List.copyOf(breadcrumbs);
    }
}
