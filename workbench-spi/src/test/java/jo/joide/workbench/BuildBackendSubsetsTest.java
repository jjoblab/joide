package jo.joide.workbench;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Le défaut du contrat de répertoire PAR SOUS-ENSEMBLE (Forge v0.2.28+, protocole
 * 6 — D-23) : un backend qui ne le supporte pas REFUSE proprement — le mode
 * terminal, un moteur trop ancien — et une demande vide retombe sur la vue
 * complète (le refus de la D-22, pas un crash). L'appelant dégrade, il ne devine
 * jamais.
 */
class BuildBackendSubsetsTest {

    /** Le backend minimal : un mode sans répertoire du tout (le Gradle d'aujourd'hui). */
    private static BuildBackend sansRépertoire() {
        return new BuildBackend() {
            @Override public WorkMode mode() {
                return WorkMode.GRADLE;
            }

            @Override public Capabilities capabilities() {
                return new Capabilities(false, true, false, false, true, List.of());
            }

            @Override public InstallState installState() {
                return InstallState.NOT_INSTALLED;
            }

            @Override public CompletableFuture<BuildSession> build(BuildRequest request) {
                return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("pas de build ici"));
            }
        };
    }

    @Test
    void leDéfautRefuseLeSousEnsembleAvecLeRemède() {
        CompletableFuture<List<TaskInfo>> futur = sansRépertoire()
                .listTasks("/projets/demo", List.of("core"));
        try {
            futur.get(2, TimeUnit.SECONDS);
            fail("un backend sans sous-ensembles doit refuser, pas répondre");
        } catch (Exception échecAttendu) {
            String message = échecAttendu.getCause() != null
                    ? échecAttendu.getCause().getMessage() : String.valueOf(échecAttendu);
            assertTrue(message.contains("modules"),
                    "le refus nomme le sous-ensemble : " + message);
            assertTrue(message.contains("GRADLE"),
                    "le refus nomme le mode fautif : " + message);
        }
    }

    @Test
    void laDemandeVideRetombeSurLaVueComplète() {
        // La liste vide (ou nulle) n'est pas un sous-ensemble : c'est la vue
        // complète — le défaut délègue, et le refus est alors celui de la D-22.
        List<List<String>> formes = new java.util.ArrayList<>();
        formes.add(List.of());
        formes.add(null);
        for (List<String> forme : formes) {
            CompletableFuture<List<TaskInfo>> futur = sansRépertoire()
                    .listTasks("/projets/demo", forme);
            try {
                futur.get(2, TimeUnit.SECONDS);
                fail("le backend sans répertoire refuse aussi la vue complète");
            } catch (Exception échecAttendu) {
                String message = échecAttendu.getCause() != null
                        ? échecAttendu.getCause().getMessage() : String.valueOf(échecAttendu);
                assertTrue(message.contains("Répertoire de tâches non supporté"),
                        "le refus est celui de la vue complète (D-22) : " + message);
            }
        }
    }
}
