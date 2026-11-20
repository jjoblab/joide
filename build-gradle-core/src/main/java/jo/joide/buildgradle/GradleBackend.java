package jo.joide.buildgradle;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import jo.joide.workbench.*;

/**
 * Backend Gradle : lance les builds dans le terminal (processus séparé, ADR-008),
 * parse les diagnostics de compilation ({@link AnalyseurSortieGradle}) et se connecte
 * au même port {@link BuildBackend} que Forge. L'exécution réelle (service de
 * terminal, session shell) est câblée côté Android (L6) ; l'analyse est testable
 * sans appareil.
 */
public final class GradleBackend implements BuildBackend {

    @Override
    public ModeTravail mode() { return ModeTravail.GRADLE; }

    @Override
    public Capacites capacites() {
        return new Capacites(false, true, true, true, true, List.of(
                "Plus lourd : environnement Linux complet à télécharger",
                "Premier build plus lent (daemon désactivé par défaut, ADR-012)"));
    }

    @Override
    public EtatInstallation etatInstallation() { return EtatInstallation.NON_INSTALLE; }

    @Override
    public CompletableFuture<BuildSession> build(BuildRequest requete) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Exécution Gradle dans le terminal : câblage Android L6 (voir limites §4)"));
    }
}
