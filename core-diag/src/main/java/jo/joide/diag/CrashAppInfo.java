package jo.joide.diag;

/**
 * Identité du build dans un rapport de plantage (portage CodeIDE §5.8).
 *
 * @param versionName    nom de version affiché (BuildConfig côté coquille)
 * @param versionCode    code de version numérique
 * @param buildType      type de build (debug, release)
 * @param applicationId  identifiant d'application
 */
public record CrashAppInfo(String versionName, long versionCode, String buildType,
        String applicationId) {
}
