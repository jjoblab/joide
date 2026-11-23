package jo.joide.workbench;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Port unique de build consommé par l'atelier (Console, Problèmes, actions Exécuter),
 * quel que soit le mode actif (conception §4). Deux implémentations : ForgeBackend
 * (module build-forge), GradleBackend (module build-gradle-core).
 */
public interface BuildBackend {

    WorkMode mode();

    Capabilities capabilities();

    InstallState installState();

    /** Lance un build ; les événements arrivent par la session. */
    CompletableFuture<BuildSession> build(BuildRequest request);

    /**
     * Le répertoire des tâches d'un projet (Forge v0.2.27+, protocole 5 — D-22 de
     * Forge) : le MOTEUR énumère ce qui peut tourner, l'atelier affiche. Une simple
     * LECTURE — jamais un build, rien ne s'exécute. Le mode Gradle ne le supporte
     * pas encore (terminal) : le futur échoue proprement, l'appelant dégrade.
     *
     * @param projectRoot racine du projet (dossier contenant forge.json).
     * @return le futur des descripteurs de tâches (concrets + cycle de vie).
     */
    default CompletableFuture<List<TaskInfo>> listTasks(String projectRoot) {
        CompletableFuture<List<TaskInfo>> refus = new CompletableFuture<>();
        refus.completeExceptionally(new UnsupportedOperationException(
                "Répertoire de tâches non supporté par le mode "
                        + mode() + " (moteur trop ancien ou mode terminal)"));
        return refus;
    }
}
