package jo.joide.diag;

/**
 * Résumé d'un rapport de plantage (portage CodeIDE §5.8) : ce que la liste
 * affiche sans lire le rapport entier — l'identité (id), la date, le type,
 * la classe de l'exception et son premier message, l'état « consulté ».
 *
 * @param id identifiant unique du rapport
 * @param type nature du plantage
 * @param timestampMillis horodatage du plantage
 * @param exceptionClassName nom simple de la classe de l'exception
 * @param shortMessage premier message de l'exception, borné
 * @param reviewed {@code true} si l'utilisateur a déjà consulté le rapport
 */
public record CrashReportSummary(String id, CrashType type, long timestampMillis,
        String exceptionClassName, String shortMessage, boolean reviewed) {
}
