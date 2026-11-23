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

    /**
     * Le répertoire des tâches d'un SOUS-ENSEMBLE de modules (Forge v0.2.28+,
     * protocole 6 — D-23 de Forge) : l'équivalent de {@code gradlew :core:tasks}.
     * Le moteur filtre le graphe aux modules demandés et y dérive leurs agrégats
     * PROPRES ({@code core:assemble}, {@code core:clean}) — jamais l'agrégat
     * global redéfini en silence. Une simple LECTURE, comme la vue complète.
     *
     * <p>Le défaut est honnête : un backend sans sous-ensembles REFUSE (le mode
     * terminal, un moteur trop ancien) ; une demande vide retombe sur la vue
     * complète ({@link #listTasks(String)}). Face à un moteur qui parle encore le
     * protocole 5, la liste part quand même (fin de charge) et le moteur répond la
     * vue complète — dégradation sans échec.</p>
     *
     * @param projectRoot racine du projet (dossier contenant forge.json).
     * @param modules     modules demandés (noms sans préfixe, ex. {@code app}) ;
     *                    vide ou nul : tout le projet.
     * @return le futur des descripteurs des tâches du sous-ensemble.
     */
    default CompletableFuture<List<TaskInfo>> listTasks(String projectRoot,
            List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return listTasks(projectRoot);
        }
        CompletableFuture<List<TaskInfo>> refus = new CompletableFuture<>();
        refus.completeExceptionally(new UnsupportedOperationException(
                "Répertoire par modules non supporté par le mode "
                        + mode() + " (moteur trop ancien ou mode terminal)"));
        return refus;
    }
}
