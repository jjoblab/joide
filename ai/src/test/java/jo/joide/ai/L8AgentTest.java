package jo.joide.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot L8 : la porte interactive (diff dans l'invite, trois décisions, session,
 * défaut sûr) et l'outil de build de l'agent (via le port BuildRunner — jamais
 * un moteur concurrent).
 */
class L8AgentTest {

    @Test
    void interactiveGateAsksWithPreviewAndAllowsOnce() {
        InteractivePermissionGate gate = new InteractivePermissionGate();
        List<PermissionGate.PermissionRequest> asked = new CopyOnWriteArrayList<>();
        // Décideur : approuve une fois, en vérifiant que l'invite porte le diff.
        gate.setDecider((request, onAnswer) -> {
            asked.add(request);
            onAnswer.accept(InteractivePermissionGate.Decision.ALLOW_ONCE);
        });
        PermissionGate.PermissionRequest request = new PermissionGate.PermissionRequest(
                "ecrire_fichier", "Main.java", "avant → après (diff)");

        assertTrue(gate.authorize(request), "ALLOW_ONCE autorise");
        assertTrue(gate.authorize(request), "ALLOW_ONCE n'autorise pas la session entière ? si, pour ce 2e appel elle re-demandra");
        assertEquals(2, asked.size(), "chaque mutation repasse la porte (ALLOW_ONCE ≠ session)");
        assertEquals("avant → après (diff)", asked.get(0).preview(),
                "le diff est dans l'invite");
    }

    @Test
    void interactiveGateAllowSessionCoversTheTurn() {
        InteractivePermissionGate gate = new InteractivePermissionGate();
        List<PermissionGate.PermissionRequest> asked = new CopyOnWriteArrayList<>();
        gate.setDecider((request, onAnswer) -> {
            asked.add(request);
            onAnswer.accept(InteractivePermissionGate.Decision.ALLOW_SESSION);
        });
        PermissionGate.PermissionRequest request = new PermissionGate.PermissionRequest(
                "supprimer_fichier", "Old.java", null);

        assertTrue(gate.authorize(request));
        assertTrue(gate.authorize(request), "ALLOW_SESSION couvre le reste de la session");
        assertEquals(1, asked.size(), "une seule invite pour la session");
        gate.resetSession();
        assertTrue(gate.authorize(request), "après reset la porte re-demande…");
        assertEquals(2, asked.size(), "…et le demande effectivement");
    }

    @Test
    void interactiveGateSafeDefaultsRefuse() {
        InteractivePermissionGate gate = new InteractivePermissionGate();
        // Aucun décideur posé (dialogue fermé) : refus — défaut sûr.
        assertFalse(gate.authorize(new PermissionGate.PermissionRequest(
                "creer_fichier", "X.java", "creation")));
        // Décideur cassé : refus.
        gate.setDecider((request, onAnswer) -> {
            throw new IllegalStateException("UI morte");
        });
        assertFalse(gate.authorize(new PermissionGate.PermissionRequest(
                "ecrire_fichier", "Y.java", null)));
    }

    @Test
    void interactiveGateHonorsPersistedModes() {
        InteractivePermissionGate gate = new InteractivePermissionGate();
        gate.setMode(PermissionGate.Mode.AUTO_ACCEPT);
        assertTrue(gate.authorize(new PermissionGate.PermissionRequest("t", "p", null)));
        gate.setMode(PermissionGate.Mode.PLAN_ONLY);
        assertFalse(gate.authorize(new PermissionGate.PermissionRequest("t", "p", null)));
        assertEquals(PermissionGate.Mode.PLAN_ONLY, gate.mode());
    }

    // ---- L'outil construire_module (via BuildRunner, jamais moteur concurrent) ----

    private static final class MockTransport implements LlmTransport {

        record Response(String text, List<ToolCall> calls) {}

        private final List<Response> script = new ArrayList<>();
        private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

        void respond(Response response) {
            script.add(response);
        }

        @Override
        public String fetchModelsJson(Endpoint endpoint) {
            return "{\"data\":[{\"id\":\"m\"}]}";
        }

        @Override
        public void chat(Endpoint endpoint, ChatRequest request,
                java.util.function.Consumer<StreamEvent> onEvent) {
            requests.add(request);
            Response response = script.remove(0);
            if (response.text() != null && !response.text().isEmpty()) {
                onEvent.accept(new StreamEvent.Text(response.text()));
            }
            if (response.calls() != null) {
                response.calls().forEach(c ->
                        onEvent.accept(new StreamEvent.ToolCallEvent(c)));
            }
            onEvent.accept(new StreamEvent.Finish("stop"));
        }
    }

    @Test
    void agentBuildsThroughThePortNotAConcurrentEngine() {
        MockTransport transport = new MockTransport();
        List<String> builtRoots = new CopyOnWriteArrayList<>();
        // BuildRunner fake : l'hôte l'adapte au BuildBackend du mode actif.
        BuildRunner runner = root -> {
            builtRoots.add(root);
            return new BuildRunner.BuildOutcome(true, "compileJava OK\ntâche terminée", 2);
        };
        transport.respond(new MockTransport.Response(null, List.of(
                new LlmTransport.ToolCall("b1", "construire_module",
                        "{\"project\":\"/proj/essai\"}"))));
        transport.respond(new MockTransport.Response("le build est bon", List.of()));
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();

        new AgentLoop(transport,
                new LlmTransport.Endpoint("http://l/chat/completions",
                        "http://l/models", null),
                "m", workspace, new PermissionGate.Scripted(PermissionGate.Mode.PLAN_ONLY),
                16, runner, true)
                .turn("construis", e -> { });

        assertEquals(List.of("/proj/essai"), builtRoots,
                "l'agent construit via le port (BuildBackend du mode actif)");
        // Le résultat du build est borné et rendu au modèle (2e requête le contient).
        boolean modelSawLog = transport.requests.get(1).messages().stream()
                .anyMatch(m -> "tool".equals(m.role()) && m.content() != null
                        && m.content().contains("tâche terminée"));
        assertTrue(modelSawLog, "le journal borné du build est renvoyé au modèle");
    }

    @Test
    void noBuildToolWithoutRunner() {
        MockTransport transport = new MockTransport();
        AgentLoop loop = new AgentLoop(transport,
                new LlmTransport.Endpoint("http://l/chat/completions",
                        "http://l/models", null),
                "m", new AgentWorkspace.InMemory(),
                new PermissionGate.Scripted(PermissionGate.Mode.PLAN_ONLY));
        boolean hasBuild = loop.tools().stream()
                .anyMatch(t -> "construire_module".equals(t.name()));
        assertFalse(hasBuild, "sans BuildRunner, aucun outil de build exposé");
    }
}
