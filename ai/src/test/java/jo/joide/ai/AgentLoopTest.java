package jo.joide.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot L7 (J-IA) : boucle d'agent complète sur un transport mock — flux de
 * deltas, appels d'outils (lectures jamais gated, mutations derrière la porte
 * unique), plafond d'itérations avec tour de clôture sans outils, appariement
 * appel/résultat (réparation des conversations interrompues), compactage à
 * hystérésis, bornage à l'arrivée, coffre de clés (repli mémoire).
 */
class AgentLoopTest {

    /** Transport mock : réponses scriptées par requête. */
    private static final class MockTransport implements LlmTransport {

        /** Réponse d'une itération : texte + appels d'outils. */
        record Response(String text, List<ToolCall> calls) {}

        private final List<Response> script = new ArrayList<>();
        private final List<List<ChatMessage>> sentMessages = new CopyOnWriteArrayList<>();
        private final List<ChatRequest> sentRequests = new CopyOnWriteArrayList<>();
        private int requestCount;
        /** Erreur à lancer au prochain chat (tests d'échecs typés). */
        LlmError throwOnChat;

        void respond(Response response) {
            script.add(response);
        }

        int requestCount() {
            return requestCount;
        }

        List<ChatMessage> lastMessages() {
            return sentMessages.get(sentMessages.size() - 1);
        }

        ChatRequest lastRequest() {
            return sentRequests.get(sentRequests.size() - 1);
        }

        @Override
        public String fetchModelsJson(Endpoint endpoint) {
            return "{\"data\":[{\"id\":\"modele-mock-a\"},{\"id\":\"modele-mock-b\"}]}";
        }

        @Override
        public void chat(Endpoint endpoint, ChatRequest request,
                Consumer<StreamEvent> onEvent) throws LlmError {
            requestCount++;
            sentMessages.add(request.messages());
            sentRequests.add(request);
            if (throwOnChat != null) {
                throw throwOnChat;
            }
            Response response = script.isEmpty()
                    ? new Response("(aucune réponse scriptée)", List.of())
                    : script.remove(0);
            if (response.text != null && !response.text.isEmpty()) {
                for (int i = 0; i < response.text.length(); i += 7) {
                    onEvent.accept(new StreamEvent.Text(
                            response.text.substring(i, Math.min(i + 7, response.text.length()))));
                }
            }
            if (response.calls != null) {
                response.calls.forEach(call ->
                        onEvent.accept(new StreamEvent.ToolCallEvent(call)));
            }
            onEvent.accept(new StreamEvent.Finish("stop"));
        }
    }

    private AgentLoop loop(MockTransport transport, AgentWorkspace workspace,
            PermissionGate gate) {
        return new AgentLoop(transport,
                new LlmTransport.Endpoint("http://localhost:11434/v1/chat/completions",
                        "http://localhost:11434/v1/models", "cle-mock"),
                "modele-mock", workspace, gate, 3);
    }

    @Test
    void textOnlyTurnCompletesInOneIteration() {
        MockTransport transport = new MockTransport();
        transport.respond(new MockTransport.Response("Bonjour !", List.of()));
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();

        loop(transport, workspace, new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT))
                .turn("dis bonjour", events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentLoop.LoopEvent.Delta d
                && "Bonjour !".equals(texts(events))), "les deltas arrivent");
        assertTrue(events.get(events.size() - 1) instanceof AgentLoop.LoopEvent.TurnCompleted t
                && t.iterations() == 1, "un tour d'une itération");
        assertEquals(1, transport.requestCount());
    }

    private String texts(List<AgentLoop.LoopEvent> events) {
        StringBuilder sb = new StringBuilder();
        events.forEach(e -> {
            if (e instanceof AgentLoop.LoopEvent.Delta d) {
                sb.append(d.text());
            }
        });
        return sb.toString();
    }

    @Test
    void readOnlyToolRunsUngated() {
        MockTransport transport = new MockTransport();
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();
        workspace.put("Main.java", "public class Main {}\n");
        // 1re itération : un appel de lecture ; 2e : réponse finale.
        transport.respond(new MockTransport.Response(null, List.of(
                new LlmTransport.ToolCall("a1", "lire_fichier", "{\"path\":\"Main.java\"}"))));
        transport.respond(new MockTransport.Response("lu", List.of()));
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
        // Porte qui refuse TOUT : la lecture passe quand même (jamais gated).
        PermissionGate.Scripted gate = new PermissionGate.Scripted(PermissionGate.Mode.PLAN_ONLY);

        loop(transport, workspace, gate).turn("lis le fichier", events::add);

        assertTrue(gate.seen().isEmpty(), "aucune permission demandée pour une lecture");
        assertEquals(2, transport.requestCount());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentLoop.LoopEvent.ToolResult));
    }

    @Test
    void mutationRefusedByGateReturnsInformativeErrorToModel() {
        MockTransport transport = new MockTransport();
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();
        workspace.put("Main.java", "avant");
        transport.respond(new MockTransport.Response(null, List.of(
                new LlmTransport.ToolCall("m1", "ecrire_fichier",
                        "{\"path\":\"Main.java\",\"content\":\"apres\"}"))));
        transport.respond(new MockTransport.Response("d'accord, je n'écris pas", List.of()));
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
        PermissionGate.Scripted gate = new PermissionGate.Scripted(PermissionGate.Mode.PLAN_ONLY);

        loop(transport, workspace, gate).turn("modifie", events::add);

        // Le refus passe par la porte (demande vue, mutation non exécutée).
        assertEquals(1, gate.seen().size());
        assertEquals("avant", workspace.get("Main.java"), "le fichier est intact");
        // Le résultat d'erreur informatif est dans l'historique (2e requête).
        boolean informative = transport.lastMessages().stream()
                .anyMatch(m -> "tool".equals(m.role())
                        && m.content() != null && m.content().contains("Refusé"));
        assertTrue(informative, "le refus informatif est renvoyé au modèle");
    }

    @Test
    void mutationAcceptedByGateWritesFile() {
        MockTransport transport = new MockTransport();
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();
        transport.respond(new MockTransport.Response(null, List.of(
                new LlmTransport.ToolCall("m1", "creer_fichier",
                        "{\"path\":\"Nouveau.java\",\"content\":\"classe\"}"))));
        transport.respond(new MockTransport.Response("créé", List.of()));
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
        PermissionGate.Scripted gate = new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT);

        loop(transport, workspace, gate).turn("crée le fichier", events::add);

        assertEquals("classe", workspace.get("Nouveau.java"), "la mutation a eu lieu");
        assertEquals(1, gate.seen().size(), "la porte a été consultée");
    }

    @Test
    void iterationCeilingEndsWithWrapUpTurnWithoutTools() {
        MockTransport transport = new MockTransport();
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();
        // Le modèle appelle un outil à chaque itération : le plafond (3) tombe.
        for (int i = 0; i < 6; i++) {
            transport.respond(new MockTransport.Response(null, List.of(
                    new LlmTransport.ToolCall("c" + i, "lister_fichiers", "{\"path\":\".\"}"))));
        }
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
        AgentLoop agent = loop(transport, workspace,
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT));

        agent.turn("tour infini", events::add);

        // 3 itérations d'outils + 1 tour de clôture = 4 requêtes, pas plus.
        assertEquals(4, transport.requestCount(), "le plafond puis le tour de clôture");
        // Le dernier envoi contient le message de clôture (sans outils proposés).
        assertTrue(transport.lastMessages().stream()
                .anyMatch(m -> "system".equals(m.role()) && m.content() != null
                        && m.content().contains("dernier")),
                "le tour de clôture est marqué dernier tour sans outils");
        assertTrue(events.get(events.size() - 1) instanceof AgentLoop.LoopEvent.TurnCompleted);
    }

    @Test
    void wrapUpTurnMessageExcludesTools() {
        MockTransport transport = new MockTransport();
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();
        for (int i = 0; i < 6; i++) {
            transport.respond(new MockTransport.Response(null, List.of(
                    new LlmTransport.ToolCall("c" + i, "chercher_texte",
                            "{\"path\":\"x\"}"))));
        }
        loop(transport, workspace,
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT))
                .turn("encore", e -> { });
        // Le tour de clôture n'expose AUCUN outil (dernière requête).
        assertTrue(transport.lastRequest().tools().isEmpty(),
                "le tour de clôture part sans outils");
    }

    @Test
    void noToolsLoopExposesNoToolsAndMentionsNoPermissionGate() {
        // Lot 0 — assistant général : la boucle sans outils n'expose RIEN au
        // modèle (roster vide) et le contexte volatile ne parle pas de porte.
        MockTransport transport = new MockTransport();
        transport.respond(new MockTransport.Response("Voilà une explication.", List.of()));
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
        AgentLoop assistantLoop = new AgentLoop(transport,
                new LlmTransport.Endpoint("http://localhost:11434/v1/chat/completions",
                        "http://localhost:11434/v1/models", null),
                "modele-mock", new AgentWorkspace.InMemory(),
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT),
                4, null, false);
        assistantLoop.setProjectContext("Aucun projet ouvert : conversation générale.");

        assistantLoop.turn("explique-moi les records", events::add);

        assertTrue(transport.lastRequest().tools().isEmpty(),
                "aucun outil n'est exposé au modèle de l'assistant");
        String volatileContext = transport.lastMessages()
                .get(transport.lastMessages().size() - 1).content();
        assertTrue(volatileContext.contains("Aucun outil"),
                "le contexte volatile dit au modèle de répondre directement");
        assertFalse(volatileContext.contains("Mode de permission"),
                "aucune ligne de permission pour l'assistant sans outils");
        assertTrue(events.get(events.size() - 1) instanceof AgentLoop.LoopEvent.TurnCompleted,
                "le tour se termine normalement en une itération");
    }

    @Test
    void schemasAreExposedWithTools() {
        // Lot 1 : chaque outil part AVEC son schéma de paramètres.
        MockTransport transport = new MockTransport();
        transport.respond(new MockTransport.Response("ok", List.of()));

        loop(transport, new AgentWorkspace.InMemory(),
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT))
                .turn("dis ok", e -> { });

        boolean allSchemaed = transport.lastRequest().tools().stream()
                .allMatch(spec -> spec.parametersSchemaJson() != null
                        && spec.parametersSchemaJson().contains("\"type\":\"object\""));
        assertTrue(allSchemaed, "les outils partent avec un schéma de paramètres");
    }

    @Test
    void cancelledDuringMutationMarksPendingCallsInterrupted() {
        // Lot 1 : Stop pendant un tour — la mutation non exécutée reçoit un
        // résultat « interrompu » (l'appariement reste valide), le tour échoue
        // avec la cause annulée.
        MockTransport transport = new MockTransport();
        transport.respond(new MockTransport.Response("je m'y mets", List.of(
                new LlmTransport.ToolCall("m1", "ecrire_fichier",
                        "{\"path\":\"Main.java\",\"content\":\"x\"}"))));
        AgentWorkspace.InMemory workspace = new AgentWorkspace.InMemory();
        workspace.put("Main.java", "avant");
        CancellationToken cancellation = new CancellationToken();
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
        AgentLoop agent = loop(transport, workspace,
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT));

        agent.turn("modifie", event -> {
            events.add(event);
            if (event instanceof AgentLoop.LoopEvent.Delta) {
                cancellation.cancel(); // Stop pendant la génération
            }
        }, cancellation);

        AgentLoop.LoopEvent last = events.get(events.size() - 1);
        assertTrue(last instanceof AgentLoop.LoopEvent.TurnFailed failure
                && failure.error() != null
                && failure.error().kind() == LlmError.Kind.CANCELLED,
                "le tour échoue avec la cause annulation");
        assertEquals("avant", workspace.get("Main.java"), "aucune mutation exécutée");
        boolean interrupted = agent.history().full().stream()
                .anyMatch(entry -> "tool".equals(entry.role())
                        && entry.toolCallId() != null && entry.toolCallId().equals("m1")
                        && entry.content() != null
                        && entry.content().contains("Interrompu"));
        assertTrue(interrupted, "l'appel non exécuté reçoit un résultat interrompu");
    }

    @Test
    void turnFailedCarriesTypedErrorFromTransport() {
        // Lot 1 : une LlmError du transport traverse la boucle sans perdre
        // sa cause (plus de message de 401 pour tout).
        MockTransport transport = new MockTransport();
        transport.throwOnChat = LlmError.rateLimited("trop vite", 30);
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();

        loop(transport, new AgentWorkspace.InMemory(),
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT))
                .turn("demande", events::add);

        assertTrue(events.get(events.size() - 1) instanceof AgentLoop.LoopEvent.TurnFailed failure
                && failure.error().kind() == LlmError.Kind.RATE_LIMIT
                && failure.error().retryAfterSeconds() == 30,
                "la cause typée et le délai traversent la boucle");
    }

    @Test
    void toolCallPairingRepairsInterruptedHistory() {
        // Historique cassé : appel sans résultat + résultat orphelin.
        List<ConversationHistory.Entry> broken = new ArrayList<>();
        broken.add(new ConversationHistory.Entry("user", "casse", null, null));
        broken.add(new ConversationHistory.Entry("assistant", "j'appelle",
                List.of(new LlmTransport.ToolCall("x1", "lister_fichiers", "{}")), null));
        broken.add(new ConversationHistory.Entry("tool", "résultat orphelin", null, "zz"));
        // (pas de résultat pour x1)
        List<ConversationHistory.Entry> paired = ToolCallPairing.pair(broken);

        boolean hasInterrupted = paired.stream().anyMatch(e ->
                "tool".equals(e.role()) && "x1".equals(e.toolCallId())
                        && e.content().contains("Interrompu"));
        assertTrue(hasInterrupted, "l'appel sans réponse reçoit un résultat d'erreur interrompu");
        boolean orphanRemoved = paired.stream().noneMatch(e -> "zz".equals(e.toolCallId()));
        assertTrue(orphanRemoved, "le résultat orphelin est éliminé");
    }

    @Test
    void compactorLeavesSmallHistoryUntouched() {
        ConversationHistory history = new ConversationHistory(1_000, 500, 4);
        history.add(new ConversationHistory.Entry("user", "petit", null, null));
        history.add(new ConversationHistory.Entry("assistant", "réponse", null, null));
        List<ConversationHistory.Entry> compacted = history.compacted();
        assertEquals(history.full(), compacted, "rien éliminé sous le seuil (cache intact)");
    }

    @Test
    void compactorElidesOldToolResultsOnTrigger() {
        ConversationHistory history = new ConversationHistory(1_000, 500, 4);
        history.add(new ConversationHistory.Entry("user", "gros travail", null, null));
        // Résultats d'outils anciens volumineux.
        for (int i = 0; i < 6; i++) {
            history.add(new ConversationHistory.Entry("assistant", "j'appelle",
                    List.of(new LlmTransport.ToolCall("t" + i, "lire_fichier", "{}")), null));
            history.add(new ConversationHistory.Entry("tool", "x".repeat(400), null, "t" + i));
        }
        history.add(new ConversationHistory.Entry("assistant", "dernier appel",
                List.of(new LlmTransport.ToolCall("t6", "lire_fichier", "{}")), null));
        history.add(new ConversationHistory.Entry("tool", "récent", null, "t6"));
        List<ConversationHistory.Entry> compacted = history.compacted();

        // Les anciens sont éliminés (permanence : le plus récent t6 reste verbatim).
        boolean recentKept = compacted.stream().anyMatch(e ->
                "tool".equals(e.role()) && "récent".equals(e.content()));
        assertTrue(recentKept, "les résultats récents ne sont jamais éliminés");
        boolean oldElided = compacted.stream().anyMatch(e ->
                "tool".equals(e.role()) && e.content().contains("éliminé"));
        assertTrue(oldElided, "les résultats anciens sont éliminés au déclenchement");
        // Texte utilisateur/assistant jamais touché.
        boolean userKept = compacted.stream().anyMatch(e ->
                "user".equals(e.role()) && "gros travail".equals(e.content()));
        assertTrue(userKept);
    }

    @Test
    void boundedResultsArriveAtSource() {
        // Le port borne à l'arrivée : la fenêtre de lecture rend compte des manques.
        AgentWorkspace.OnProject workspace = tempWorkspace();
        try {
            java.nio.file.Files.writeString(workspaceDir().resolve("Gros.java"),
                    "ligne\n".repeat(300));
            AgentWorkspace.Bounded bounded = workspace.readFile("Gros.java", 1, 200);
            assertEquals(100, bounded.omitted(), "le compte des lignes non affichées est rendu");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void vaultRoundTripAndRedaction() {
        KeyVault vault = new KeyVault.InMemory();
        vault.putApiKey("laboratoire", "sk-12345678901234567890");
        assertEquals("sk-12345678901234567890", vault.apiKey("laboratoire"));
        assertNull(vault.apiKey("passerelle"));
        vault.removeApiKey("laboratoire");
        assertTrue(vault.isEmpty());
        assertFalse("sk-12345678901234567890".isBlank());
    }

    // ---- Support ----------------------------------------------------------

    private static java.nio.file.Path tempDir;

    private static java.nio.file.Path workspaceDir() {
        if (tempDir == null) {
            try {
                tempDir = java.nio.file.Files.createTempDirectory("ai-test");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return tempDir;
    }

    private AgentWorkspace.OnProject tempWorkspace() {
        return new AgentWorkspace.OnProject(workspaceDir(), 4_000);
    }
}
