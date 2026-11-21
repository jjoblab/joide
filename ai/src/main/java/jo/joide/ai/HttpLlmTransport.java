package jo.joide.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jo.joide.ai.json.Json;
import jo.joide.ai.json.JsonException;

/**
 * Transport HTTP/SSE de production (réécrit au Lot 1 autour de {@link Json}
 * et {@link SseParser}) : format passerelle compatible OpenAI —
 * {@code POST {base}/v1/chat/completions} en flux, {@code GET {base}/v1/models}.
 *
 * <p>Corrections apportées par rapport à l'ancienne analyse à la main :
 * appels d'outils **cumulés par {@code index}** sur plusieurs fragments,
 * guillemets échappés, sauts de ligne, points de code Unicode et accolades
 * dans les arguments ; deltas de **raisonnement** séparés du texte ;
 * {@code usage} en fin de flux ; vraie raison d'arrêt ; lignes de
 * **commentaire SSE** ignorées ; **erreur en cours de flux** détectée
 * (objet {@code error}) ; **annulation** (jeton → fermeture de la connexion) ;
 * **reprise avec backoff** uniquement tant qu'aucune sortie n'a été émise ;
 * erreurs **typées** ({@link LlmError}) avec {@code Retry-After}, corps
 * rédigé par {@link Secrets} — jamais la clé.</p>
 */
public final class HttpLlmTransport implements LlmTransport {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_RETRIES = 2;
    private static final long FIRST_BACKOFF_MS = 400;

    /** Schéma d'objet vide (repli quand un schéma d'outil est invalide). */
    private static final java.util.Map<String, Object> EMPTY_SCHEMA = schemaMap();

    private static java.util.Map<String, Object> schemaMap() {
        java.util.Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    @Override
    public String fetchModelsJson(Endpoint endpoint) throws Exception {
        HttpURLConnection connection = open("GET", endpoint.modelsUrl(),
                endpoint.apiKey(), endpoint.headers(), null);
        int status = connection.getResponseCode();
        if (status != 200) {
            throw LlmError.fromStatus(status, readBody(connection),
                    header(connection, "Retry-After"));
        }
        return readBody(connection);
    }

    @Override
    public void chat(Endpoint endpoint, ChatRequest request,
            java.util.function.Consumer<StreamEvent> onEvent) throws Exception {
        CancellationToken cancellation = request.cancellation();
        // Reprises : uniquement tant qu'aucune sortie n'a été émise (un
        // rejeu après des deltas visibles dupliquerait la réponse).
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            EmissionGuard guard = new EmissionGuard(onEvent, cancellation);
            try {
                streamOnce(endpoint, request, guard);
                guard.flushFinish();
                return;
            } catch (LlmError error) {
                if (error.kind() == LlmError.Kind.CANCELLED
                        || guard.hasEmitted() || !retryable(error) || attempt == MAX_RETRIES) {
                    guard.flushFinish();
                    throw error;
                }
                long backoff = backoffFor(error, attempt);
                sleepInterruptibly(backoff, cancellation);
            } catch (java.io.IOException e) {
                // Annulation : la fermeture de la connexion interrompt la
                // lecture — c'est une annulation, pas une panne réseau.
                guard.flushFinish();
                if (cancellation != null && cancellation.isCancelled()) {
                    throw LlmError.of(LlmError.Kind.CANCELLED, null);
                }
                if (guard.hasEmitted() || attempt == MAX_RETRIES) {
                    throw LlmError.of(LlmError.Kind.NETWORK, Secrets.redact(
                            e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
                }
                sleepInterruptibly(backoffFor(
                        LlmError.of(LlmError.Kind.NETWORK, null), attempt), cancellation);
            }
        }
    }

    /** Une tentative complète de requête en flux. */
    private void streamOnce(Endpoint endpoint, ChatRequest request,
            EmissionGuard guard) throws Exception {
        CancellationToken cancellation = request.cancellation();
        HttpURLConnection connection = open("POST", endpoint.chatUrl(),
                endpoint.apiKey(), endpoint.headers(), cancellation);
        try {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(requestJson(request).getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw LlmError.fromStatus(status, readBody(connection),
                        header(connection, "Retry-After"));
            }
            readStream(connection, guard, cancellation);
        } finally {
            connection.disconnect();
        }
    }

    // ---- Lecture du flux SSE -------------------------------------------------

    /** Lit le corps en flux, nourrit le parseur SSE, traduit les événements. */
    private void readStream(HttpURLConnection connection, EmissionGuard guard,
            CancellationToken cancellation) throws Exception {
        ToolCallAccumulator calls = new ToolCallAccumulator();
        SseParser parser = new SseParser();
        InputStream stream = connection.getInputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw LlmError.of(LlmError.Kind.CANCELLED, null);
            }
            for (String data : parser.feed(buffer, read)) {
                handleData(data, calls, guard);
            }
        }
        for (String data : parser.flush()) {
            handleData(data, calls, guard);
        }
        // Fin de flux sans [DONE] : les appels d'outils accumulés se rendent.
        calls.emitPending(guard::accept);
    }

    /** Traduit un événement SSE {@code data} en événements du port. */
    private void handleData(String data, ToolCallAccumulator calls, EmissionGuard guard)
            throws LlmError {
        if (data.equals("[DONE]")) {
            calls.emitPending(guard::accept);
            return;
        }
        Map<String, Object> event;
        try {
            event = Json.parseObject(data);
        } catch (JsonException e) {
            // Un fragment non JSON est ignoré (jamais un crash du tour).
            return;
        }
        // Erreur en cours de flux : le fournisseur l'envoie comme événement data.
        if (event.get("error") instanceof Map<?, ?> error) {
            throw midStreamError(error);
        }
        if (event.get("usage") instanceof Map<?, ?> usage) {
            guard.accept(new StreamEvent.UsageEvent(parseUsage(usage)));
        }
        if (event.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice) {
            if (choice.get("delta") instanceof Map<?, ?> delta) {
                if (delta.get("content") instanceof String text && !text.isEmpty()) {
                    guard.accept(new StreamEvent.Text(text));
                }
                // Raisonnement : champ selon le fournisseur (les deux sont lus,
                // un seul est présent en pratique — jamais mélangé au texte).
                for (String reasoningField : new String[] {"reasoning", "reasoning_content"}) {
                    if (delta.get(reasoningField) instanceof String reasoning
                            && !reasoning.isEmpty()) {
                        guard.accept(new StreamEvent.Reasoning(reasoning));
                        break;
                    }
                }
                if (delta.get("tool_calls") instanceof List<?> fragments) {
                    calls.absorb(fragments);
                }
            }
            if (choice.get("finish_reason") instanceof String reason && !reason.isEmpty()) {
                calls.emitPending(guard::accept);
                guard.accept(new StreamEvent.Finish(reason));
            }
        }
    }

    /** Erreur envoyée au milieu du flux (objet error du fournisseur). */
    private LlmError midStreamError(Map<?, ?> error) {
        Object message = error.get("message");
        Object code = error.get("code");
        String detail = Secrets.redact((message instanceof String m ? m : "")
                + (code instanceof Number n ? " (code " + n.intValue() + ")" : ""));
        // Le code du fournisseur garde sa cause (401/402/429), sinon l'erreur
        // est une coupure en cours de génération.
        if (code instanceof Number n) {
            switch (n.intValue()) {
                case 401, 403 -> {
                    return LlmError.of(LlmError.Kind.AUTH, detail);
                }
                case 402 -> {
                    return LlmError.of(LlmError.Kind.INSUFFICIENT_CREDITS, detail);
                }
                case 429 -> {
                    return LlmError.rateLimited(detail, 0);
                }
                default -> {
                    return LlmError.of(LlmError.Kind.MID_STREAM, detail);
                }
            }
        }
        return LlmError.of(LlmError.Kind.MID_STREAM, detail);
    }

    /** Usage signalé (champs courants du format passerelle). */
    private Usage parseUsage(Map<?, ?> usage) {
        long prompt = longOf(usage.get("prompt_tokens"));
        long completion = longOf(usage.get("completion_tokens"));
        long cached = 0;
        if (usage.get("prompt_tokens_details") instanceof Map<?, ?> details
                && details.get("cached_tokens") instanceof Number n) {
            cached = n.longValue();
        }
        long reasoning = 0;
        if (usage.get("completion_tokens_details") instanceof Map<?, ?> details
                && details.get("reasoning_tokens") instanceof Number n) {
            reasoning = n.longValue();
        }
        return new Usage(prompt, completion, reasoning, cached);
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : -1;
    }

    // ---- Requête -------------------------------------------------------------

    /** Corps de la requête (objets imbriqués via le codec, plus de concaténation). */
    String requestJson(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("stream", true);
        body.put("max_tokens", request.maxTokens());
        List<Object> messages = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            if (message.content() != null) {
                item.put("content", message.content());
            }
            if (message.toolCallId() != null) {
                item.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                List<Object> calls = new ArrayList<>();
                for (ToolCall call : message.toolCalls()) {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", call.argumentsJson());
                    Map<String, Object> callItem = new LinkedHashMap<>();
                    callItem.put("id", call.id());
                    callItem.put("type", "function");
                    callItem.put("function", function);
                    calls.add(callItem);
                }
                item.put("tool_calls", calls);
            }
            messages.add(item);
        }
        body.put("messages", messages);
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Object> tools = new ArrayList<>();
            for (ToolSpec tool : request.tools()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", tool.name());
                function.put("description", tool.description());
                if (tool.parametersSchemaJson() != null && !tool.parametersSchemaJson().isEmpty()) {
                    try {
                        function.put("parameters", Json.parse(tool.parametersSchemaJson()));
                    } catch (JsonException e) {
                        // Schéma invalide : un schéma d'objet vide reste valide.
                        function.put("parameters", EMPTY_SCHEMA);
                    }
                }
                Map<String, Object> toolItem = new LinkedHashMap<>();
                toolItem.put("type", "function");
                toolItem.put("function", function);
                tools.add(toolItem);
            }
            body.put("tools", tools);
        }
        if (request.reasoningEffort() != null && !request.reasoningEffort().isEmpty()) {
            body.put("reasoning_effort", request.reasoningEffort());
        }
        // Champs propres au fournisseur (routage OpenRouter, raisonnement
        // specifique…) : fusionnés tels quels, ils priment sur les génériques.
        if (request.extras() != null && !request.extras().isEmpty()) {
            body.putAll(request.extras());
        }
        return Json.write(body);
    }

    // ---- Connexion -----------------------------------------------------------

    private HttpURLConnection open(String method, String url, String apiKey,
            java.util.Map<String, String> headers, CancellationToken cancellation)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        if (apiKey != null && !apiKey.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getValue() != null && !header.getValue().isEmpty()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
        }
        if (cancellation != null) {
            // Stop : la fermeture interrompt l'attente bloquante en lecture.
            cancellation.onCancel(connection::disconnect);
        }
        return connection;
    }

    private static String readBody(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getErrorStream() != null
                    ? connection.getErrorStream() : connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
            return body.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String header(HttpURLConnection connection, String name) {
        return connection.getHeaderField(name);
    }

    /** Une erreur se réessaie : réseau, délai, 429, 5xx du fournisseur. */
    private static boolean retryable(LlmError error) {
        switch (error.kind()) {
            case NETWORK, TIMEOUT, SERVER, RATE_LIMIT:
                return true;
            default:
                return false;
        }
    }

    /** Backoff exponentiel, allongé par Retry-After quand le fournisseur le donne. */
    private static long backoffFor(LlmError error, int attempt) {
        long exponential = FIRST_BACKOFF_MS * (1L << attempt);
        long retryAfter = error.retryAfterSeconds() * 1_000L;
        return Math.max(exponential, retryAfter);
    }

    private static void sleepInterruptibly(long millis, CancellationToken cancellation)
            throws LlmError {
        long step = 100;
        long slept = 0;
        while (slept < millis) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw LlmError.of(LlmError.Kind.CANCELLED, null);
            }
            long chunk = Math.min(step, millis - slept);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LlmError.of(LlmError.Kind.CANCELLED, null);
            }
            slept += chunk;
        }
    }

    // ---- Garde d'émission (reprise seulement avant la première sortie) -------

    /**
     * Retient si une sortie a été émise (texte, raisonnement, appel d'outil,
     * usage, fin) : condition des reprises ; bloque l'émission après
     * annulation pour ne pas rendre un fragment orphelin.
     */
    private static final class EmissionGuard {

        private final java.util.function.Consumer<StreamEvent> sink;
        private final CancellationToken cancellation;
        private volatile boolean emitted;
        private StreamEvent.Finish pendingFinish;

        EmissionGuard(java.util.function.Consumer<StreamEvent> sink,
                CancellationToken cancellation) {
            this.sink = sink;
            this.cancellation = cancellation;
        }

        boolean hasEmitted() {
            return emitted;
        }

        void accept(StreamEvent event) {
            if (cancellation != null && cancellation.isCancelled()) {
                return; // annulé : plus rien ne sort
            }
            emitted = true;
            if (event instanceof StreamEvent.Finish finish) {
                pendingFinish = finish;
                return; // la fin n'est rendue qu'à la complétion du tour
            }
            sink.accept(event);
        }

        /** Rend la fin (différée pour rester le dernier événement). */
        void flushFinish() {
            if (pendingFinish != null
                    && (cancellation == null || !cancellation.isCancelled())) {
                sink.accept(pendingFinish);
                pendingFinish = null;
            }
        }
    }

    // ---- Cumul des appels d'outils par index ---------------------------------

    /**
     * Cumule les fragments d'appels d'outils du flux : chaque fragment porte
     * un {@code index} ; l'id et le nom arrivent au premier fragment, les
     * arguments s'accumulent (chaîne) jusqu'à la fin. Les appels complets se
     * rendent dans l'ordre des index.
     */
    static final class ToolCallAccumulator {

        private final Map<Integer, StringBuilder> arguments = new LinkedHashMap<>();
        private final Map<Integer, String> ids = new LinkedHashMap<>();
        private final Map<Integer, String> names = new LinkedHashMap<>();

        /** Absorbe les fragments d'un delta. */
        void absorb(List<?> fragments) {
            for (Object fragment : fragments) {
                if (!(fragment instanceof Map<?, ?> call)) {
                    continue;
                }
                int index = call.get("index") instanceof Number n ? n.intValue() : 0;
                if (call.get("id") instanceof String id && !id.isEmpty()) {
                    ids.put(index, id);
                }
                if (call.get("function") instanceof Map<?, ?> function) {
                    if (function.get("name") instanceof String name && !name.isEmpty()) {
                        names.put(index, name);
                    }
                    if (function.get("arguments") instanceof String argument) {
                        arguments.computeIfAbsent(index, k -> new StringBuilder())
                                .append(argument);
                    }
                }
            }
        }

        /** Rend les appels complets (id, nom et arguments connus), dans l'ordre. */
        void emitPending(java.util.function.Consumer<StreamEvent> sink) {
            for (Integer index : new ArrayList<>(arguments.keySet())) {
                String id = ids.get(index);
                String name = names.get(index);
                String args = arguments.get(index).toString();
                if (id != null && name != null) {
                    sink.accept(new StreamEvent.ToolCallEvent(new ToolCall(id, name, args)));
                }
            }
            arguments.clear();
            ids.clear();
            names.clear();
        }
    }
}
