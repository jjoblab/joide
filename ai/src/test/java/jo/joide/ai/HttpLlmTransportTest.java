package jo.joide.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 1 : transport HTTP/SSE contre un vrai serveur HTTP du JDK (aucune
 * dépendance) — flux nominal, appels d'outils fragmentés sur plusieurs
 * fragments et plusieurs index, commentaires SSE, CRLF, UTF-8 multi-octets
 * coupé, [DONE], usage final, erreur HTTP avec corps, erreur en cours de
 * flux, 429 avec Retry-After, annulation en cours de flux, reprise
 * uniquement avant la première sortie.
 */
class HttpLlmTransportTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Enregistre un gestionnaire qui rend les octets donnés (SSE). */
    private void respond(BytesResponder responder) {
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            responder.respond(exchange);
        });
    }

    private interface BytesResponder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private static BytesResponder sse(byte[]... chunks) {
        return exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (byte[] chunk : chunks) {
                    out.write(chunk);
                    out.flush();
                }
            }
        };
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    /** Endpoint du faux serveur (paths au format canonique /v1). */
    private LlmTransport.Endpoint endpoint() {
        return new LlmTransport.Endpoint(base + "/v1/chat/completions",
                base + "/v1/models", "cle-test");
    }

    private List<LlmTransport.StreamEvent> chat(List<byte[]> chunks) throws Exception {
        return chat(chunks, null);
    }

    private List<LlmTransport.StreamEvent> chat(List<byte[]> chunks,
            CancellationToken cancellation) throws Exception {
        respond(sse(chunks.toArray(new byte[0][])));
        HttpLlmTransport transport = new HttpLlmTransport();
        List<LlmTransport.StreamEvent> events = new CopyOnWriteArrayList<>();
        transport.chat(endpoint(), new LlmTransport.ChatRequest("modele", List.of(),
                List.of(), 128, null, cancellation), events::add);
        return events;
    }

    private static String texts(List<LlmTransport.StreamEvent> events) {
        StringBuilder text = new StringBuilder();
        for (LlmTransport.StreamEvent event : events) {
            if (event instanceof LlmTransport.StreamEvent.Text t) {
                text.append(t.delta());
            }
        }
        return text.toString();
    }

    // ---- Flux nominal --------------------------------------------------------

    @Test
    void nominalStreamEmitsTextsFinishAndUsage() throws Exception {
        List<byte[]> chunks = List.of(utf8(
                data("{\"choices\":[{\"delta\":{\"content\":\"Bon\"}}]}")
                + data("{\"choices\":[{\"delta\":{\"content\":\"jour\"}}]}")
                + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + data("{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40}}")
                + "data: [DONE]\n\n"));
        List<LlmTransport.StreamEvent> events = chat(chunks);

        assertEquals("Bonjour", texts(events));
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmTransport.StreamEvent.Finish f
                && "stop".equals(f.stopReason())), "vraie raison d'arrêt");
        assertTrue(events.stream().anyMatch(e -> e instanceof LlmTransport.StreamEvent.UsageEvent u
                && u.usage().promptTokens() == 100 && u.usage().completionTokens() == 40),
                "usage final signalé");
    }

    @Test
    void commentsAndKeepAliveLinesAreIgnored() throws Exception {
        List<byte[]> chunks = List.of(utf8(
                ": OPENROUTER PROCESSING\n\n"
                + data("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
                + ": encore un commentaire\n"
                + "data: [DONE]\n\n"));
        assertEquals("ok", texts(chat(chunks)));
    }

    @Test
    void crlfAndUtf8MultibyteSplitSurvive() throws Exception {
        // Un seul flux : CRLF + caractère multi-octets coupé entre deux chunks.
        byte[] full = utf8("data: {\"choices\":[{\"delta\":{\"content\":\"é✦€\"}}]}\r\n\r\n"
                + "data: [DONE]\r\n\r\n");
        List<byte[]> chunks = new ArrayList<>();
        for (byte b : full) {
            chunks.add(new byte[] {b});
        }
        assertEquals("é✦€", texts(chat(chunks)));
    }

    @Test
    void reasoningDeltasAreSeparateFromText() throws Exception {
        List<byte[]> chunks = List.of(utf8(
                data("{\"choices\":[{\"delta\":{\"reasoning\":\"je réfléchis\"}}]}")
                + data("{\"choices\":[{\"delta\":{\"content\":\"réponse\"}}]}")
                + "data: [DONE]\n\n"));
        List<LlmTransport.StreamEvent> events = chat(chunks);
        assertEquals("réponse", texts(events));
        assertTrue(events.stream().anyMatch(
                e -> e instanceof LlmTransport.StreamEvent.Reasoning r
                        && "je réfléchis".equals(r.delta())));
    }

    // ---- Appels d'outils -----------------------------------------------------

    /** Construit un événement SSE data à partir d'une valeur (via le codec). */
    private static String dataValue(Object value) {
        return "data: " + jo.joide.ai.json.Json.write(value) + "\n\n";
    }

    private static java.util.Map<String, Object> toolCallFragment(int index, String id,
            String name, String arguments) {
        java.util.Map<String, Object> function = new java.util.LinkedHashMap<>();
        if (name != null) {
            function.put("name", name);
        }
        if (arguments != null) {
            function.put("arguments", arguments);
        }
        java.util.Map<String, Object> call = new java.util.LinkedHashMap<>();
        call.put("index", index);
        if (id != null) {
            call.put("id", id);
        }
        call.put("function", function);
        return choiceDelta(java.util.Map.of("tool_calls", java.util.List.of(call)));
    }

    private static java.util.Map<String, Object> choiceDelta(
            java.util.Map<String, Object> delta) {
        return java.util.Map.of("choices",
                java.util.List.of(java.util.Map.of("delta", delta)));
    }

    @Test
    void fragmentedToolCallsAccumulateByIndexWithQuotesNewlinesAndBraces() throws Exception {
        // Arguments cible (JSON valide avec guillemets échappés, accolades,
        // échappement de saut de ligne et Unicode) :
        String arguments = "{\"path\":\"Main.java\",\"content\":\"a = \\\"b{c}\\\";\\n"
                + "ligne suivante é✦\"}";
        // Le modèle les envoie en DEUX fragments, entrecoupés d'un second
        // appel (index 1) — le pire cas de fragmentation réelle.
        String fragmentA = "{\"path\":\"Main";
        String fragmentB = ".java\",\"content\":\"a = \\\"b{c}\\\";\\n"
                + "ligne suivante é✦\"}";
        List<byte[]> chunks = List.of(utf8(
                dataValue(toolCallFragment(0, "a1", "ecrire_fichier", null))
                + dataValue(toolCallFragment(0, null, null, fragmentA))
                + dataValue(toolCallFragment(1, "a2", "lire_fichier", null))
                + dataValue(toolCallFragment(0, null, null, fragmentB))
                + dataValue(toolCallFragment(1, null, null, "{\"path\":\"X.java\"}"))
                + dataValue(java.util.Map.of("choices", java.util.List.of(
                        java.util.Map.of("delta", new java.util.HashMap<String, Object>(),
                                "finish_reason", "tool_calls"))))
                + "data: [DONE]\n\n"));
        List<LlmTransport.StreamEvent> events = chat(chunks);

        List<LlmTransport.ToolCall> calls = new ArrayList<>();
        for (LlmTransport.StreamEvent event : events) {
            if (event instanceof LlmTransport.StreamEvent.ToolCallEvent c) {
                calls.add(c.call());
            }
        }
        assertEquals(2, calls.size(), "deux appels complets");
        assertEquals("ecrire_fichier", calls.get(0).name());
        assertEquals("lire_fichier", calls.get(1).name());
        assertEquals("a1", calls.get(0).id());
        assertEquals("a2", calls.get(1).id());
        // Les arguments reconstitués sont exactement le JSON cible : guillemets,
        // accolades, saut de ligne et Unicode intacts (aucune perte de caractère).
        assertEquals(arguments, calls.get(0).argumentsJson());
        assertEquals("{\"path\":\"X.java\"}", calls.get(1).argumentsJson());
    }

    // ---- Erreurs -------------------------------------------------------------

    @Test
    void httpErrorWithBodyBecomesTypedRedactedError() {
        respond(exchange -> {
            byte[] body = utf8("{\"error\":{\"message\":\"invalid key sk-or-v1-abcdef123456\"}}");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        HttpLlmTransport transport = new HttpLlmTransport();
        LlmError error = assertThrows(LlmError.class, () ->
                transport.chat(endpoint(), new LlmTransport.ChatRequest(
                        "m", List.of(), List.of(), 64), e -> { }));
        assertEquals(LlmError.Kind.AUTH, error.kind());
        assertFalse(error.getMessage().contains("sk-or-v1-abcdef123456"), "clé rédigée");
    }

    @Test
    void rateLimitCarriesRetryAfterHeader() {
        respond(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "17");
            byte[] body = utf8("{\"error\":\"rate limited\"}");
            exchange.sendResponseHeaders(429, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        HttpLlmTransport transport = new HttpLlmTransport();
        LlmError error = assertThrows(LlmError.class, () ->
                transport.chat(endpoint(), new LlmTransport.ChatRequest(
                        "m", List.of(), List.of(), 64), e -> { }));
        assertEquals(LlmError.Kind.RATE_LIMIT, error.kind());
        assertEquals(17, error.retryAfterSeconds());
    }

    @Test
    void midStreamErrorIsDetectedNotSilentSuccess() {
        List<byte[]> chunks = List.of(utf8(
                data("{\"choices\":[{\"delta\":{\"content\":\"début\"}}]}")
                + data("{\"error\":{\"message\":\"quota dépassé\",\"code\":402}}")
                + "data: [DONE]\n\n"));
        // 402 en cours de flux : crédits insuffisants (pas un succès vide).
        try {
            chat(chunks);
            throw new AssertionError("l'erreur en cours de flux doit échouer le tour");
        } catch (LlmError error) {
            assertEquals(LlmError.Kind.INSUFFICIENT_CREDITS, error.kind());
        } catch (Exception e) {
            throw new AssertionError("cause inattendue : " + e);
        }
    }

    @Test
    void midStream429IsRateLimited() {
        List<byte[]> chunks = List.of(utf8(
                data("{\"error\":{\"message\":\"trop de requêtes\",\"code\":429}}")
                + "data: [DONE]\n\n"));
        try {
            chat(chunks);
            throw new AssertionError("devrait échouer");
        } catch (LlmError error) {
            assertEquals(LlmError.Kind.RATE_LIMIT, error.kind());
        } catch (Exception e) {
            throw new AssertionError("cause inattendue : " + e);
        }
    }

    // ---- Annulation ----------------------------------------------------------

    @Test
    void cancellationMidStreamStopsOutput() throws Exception {
        CountDownLatch firstChunkRead = new CountDownLatch(1);
        List<byte[]> chunks = List.of(
                utf8(data("{\"choices\":[{\"delta\":{\"content\":\"premier \"}}]}")));
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(chunks.get(0));
            out.flush();
            firstChunkRead.countDown();
            try {
                // Retient la connexion ouverte : seule l'annulation la ferme.
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // Fermé par le serveur à l'arrêt du test.
            }
        });
        CancellationToken cancellation = new CancellationToken();
        HttpLlmTransport transport = new HttpLlmTransport();
        List<LlmTransport.StreamEvent> events = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread worker = new Thread(() -> {
            try {
                transport.chat(endpoint(), new LlmTransport.ChatRequest(
                        "m", List.of(), List.of(), 64, null, cancellation),
                        events::add);
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        worker.start();
        firstChunkRead.await();
        cancellation.cancel();
        worker.join(5_000);
        assertFalse(worker.isAlive(), "la lecture s'interrompt");
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof LlmError error
                && error.kind() == LlmError.Kind.CANCELLED,
                "échec réel : " + failures.get(0));
        // Rien après l'annulation : jamais de fin « normale » ni de suite
        // du flux (le fragment déjà lu peut être rendu ou non selon l'instant
        // exact de l'annulation — seul compte l'arrêt propre).
        assertTrue(events.stream().noneMatch(
                e -> e instanceof LlmTransport.StreamEvent.Finish),
                "aucune fin normale après annulation");
    }

    // ---- Reprises ------------------------------------------------------------

    @Test
    void retriesServerErrorsOnlyBeforeFirstOutput() throws Exception {
        List<byte[]> stream = List.of(utf8(
                data("{\"choices\":[{\"delta\":{\"content\":\"enfin\"}}]}")
                + "data: [DONE]\n\n"));
        AtomicInteger served = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            if (served.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (byte[] chunk : stream) {
                    out.write(chunk);
                    out.flush();
                }
            }
        });
        HttpLlmTransport transport = new HttpLlmTransport();
        List<LlmTransport.StreamEvent> events = new ArrayList<>();
        transport.chat(endpoint(), new LlmTransport.ChatRequest(
                "m", List.of(), List.of(), 64), events::add);
        assertEquals(2, served.get(), "une reprise avant la première sortie");
        assertEquals("enfin", texts(events));
    }

    @Test
    void noRetryAfterFirstOutput() throws Exception {
        // Première tentative : du texte PUIS la connexion tombe (erreur).
        AtomicInteger served = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(utf8(data("{\"choices\":[{\"delta\":{\"content\":\"déjà rendu\"}}]}")));
            out.flush();
            served.incrementAndGet();
            // Ferme brutalement : IOException côté client en cours de lecture.
            out.close();
        });
        HttpLlmTransport transport = new HttpLlmTransport();
        List<LlmTransport.StreamEvent> events = new ArrayList<>();
        transport.chat(endpoint(), new LlmTransport.ChatRequest(
                "m", List.of(), List.of(), 64), events::add);
        // Une sortie a été émise : aucune reprise, le tour échoue honnêtement
        // (le transport laisse la boucle juger : ici le flux se termine sans
        // [DONE] ni finish — pas de rejeu, pas de duplication).
        assertEquals(1, served.get(), "aucune reprise après la première sortie");
        assertEquals("déjà rendu", texts(events));
    }

    // ---- listModels ----------------------------------------------------------

    @Test
    void fetchModelsJsonReturnsBodyForCatalogueParsing() throws Exception {
        server.createContext("/v1/models", exchange -> {
            byte[] body = utf8("{\"data\":[{\"id\":\"m-a\"},{\"id\":\"m-b\"}]}");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        String json = new HttpLlmTransport().fetchModelsJson(endpoint());
        assertEquals(List.of("m-a", "m-b"),
                jo.joide.ai.provider.ModelCatalog.parseIds(json));
    }

    @Test
    void extrasAreMergedIntoRequestBody() {
        HttpLlmTransport transport = new HttpLlmTransport();
        java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> reasoning = new java.util.LinkedHashMap<>();
        reasoning.put("effort", "high");
        extras.put("reasoning", reasoning);
        extras.put("models", java.util.List.of("exemple/a", "exemple/b"));
        String json = transport.requestJson(new LlmTransport.ChatRequest("m",
                List.of(LlmTransport.ChatMessage.user("q")), List.of(), 64,
                null, null, extras));
        assertTrue(json.contains("\"reasoning\":{\"effort\":\"high\"}"),
                "objet reasoning OpenRouter fusionné");
        assertTrue(json.contains("\"models\":[\"exemple/a\",\"exemple/b\"]"),
                "replis de modèles fusionnés");
    }

    @Test
    void requestJsonSerializesSchemasAndEscapes() {
        HttpLlmTransport transport = new HttpLlmTransport();
        LlmTransport.ToolSpec spec = new LlmTransport.ToolSpec("ecrire_fichier",
                "écrit un fichier \"complexe\"", "{\"type\":\"object\",\"properties\":{"
                        + "\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
        String json = transport.requestJson(new LlmTransport.ChatRequest(
                "modele", List.of(LlmTransport.ChatMessage.user("question é✦")),
                List.of(spec), 512));
        assertTrue(json.contains("\"max_tokens\":512"));
        assertTrue(json.contains("\"parameters\":{\"type\":\"object\""), "schéma sérialisé");
        assertTrue(json.contains("question é✦"), "unicode intact");
        assertTrue(json.contains("écrit un fichier \\\"complexe\\\""), "échappement correct");
    }
}
