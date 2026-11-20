package jo.joide.buildgradle;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import jo.joide.workbench.BuildBackend;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.BuildSession;
import jo.joide.workbench.Capabilities;
import jo.joide.workbench.InstallState;
import jo.joide.workbench.WorkMode;

/**
 * Backend Gradle : lance les builds dans le terminal (processus séparé, ADR-008),
 * parse les diagnostics de compilation ({@link GradleOutputParser}) et se connecte
 * au même port {@link BuildBackend} que Forge. L'exécution réelle (service de
 * terminal, session shell) est câblée côté Android (L6) ; l'analyse est testable
 * sans appareil.
 */
public final class GradleBackend implements BuildBackend {

    @Override
    public WorkMode mode() {
        return WorkMode.GRADLE;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, true, true, true, true, List.of(
                "Plus lourd : environnement Linux complet à télécharger",
                "Premier build plus lent (daemon désactivé par défaut, ADR-012)"));
    }

    @Override
    public InstallState installState() {
        return InstallState.NOT_INSTALLED;
    }

    @Override
    public CompletableFuture<BuildSession> build(BuildRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Exécution Gradle dans le terminal : câblage Android L6 (voir limites §4)"));
    }
}
