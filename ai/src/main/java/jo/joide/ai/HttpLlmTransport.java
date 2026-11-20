package jo.joide.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Transport HTTP/SSE de production : {@code HttpURLConnection} (disponible dès
 * l'API 24, aucune dépendance ajoutée — conception §8 « Java pur + transport
 * HTTP/SSE »). Le format est celui de la passerelle compatible OpenAI :
 * {@code POST {base}/v1/chat/completions} avec {@code stream:true}, deltas
 * traduits en événements neutres du port. La liste vivante interroge
 * {@code GET {base}/v1/models}.
 *
 * <p>Clé jamais loggée ; erreurs de flux remontées en message lisible (le code
 * HTTP et le corps tronqué, jamais l'en-tête d'autorisation).</p>
 */
public final class HttpLlmTransport implements LlmTransport {

    private static final int TIMEOUT_MS = 120_000;
    private static final int CONNECT_MS = 15_000;

    @Override
    public List<String> listModels(String baseUrl, String apiKey) throws Exception {
        HttpURLConnection connection = open("GET", url(baseUrl, "/v1/models"), apiKey);
        int status = connection.getResponseCode();
        if (status != 200) {
            throw new IllegalStateException("Liste des modèles : HTTP " + status);
        }
        String body = readAll(new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8)));
        List<String> models = new ArrayList<>();
        // Corps attendu : {"data":[{"id":"modele"},...]} — analyse minimale
        // sans dépendance JSON (javax.json est interdit par la règle de
        // dépendances ; les guillemets échappés n'apparaissent pas dans les ids).
        int i = body.indexOf("\"data\"");
        if (i < 0) {
            return List.of();
        }
        int from = body.indexOf('[', i);
        int cursor = from + 1;
        while (true) {
            int idAt = body.indexOf("\"id\"", cursor);
            if (idAt < 0) {
                break;
            }
            int start = body.indexOf('"', idAt + 4) + 1;
            int end = body.indexOf('"', start);
            if (start <= end) {
                models.add(body.substring(start, end));
            }
            cursor = end + 1;
        }
        return List.copyOf(models);
    }

    @Override
    public void chat(String baseUrl, String apiKey, ChatRequest request,
            java.util.function.Consumer<StreamEvent> onEvent) throws Exception {
        HttpURLConnection connection = open("POST",
                url(baseUrl, "/v1/chat/completions"), apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(requestJson(request).getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        if (status != 200) {
            String body = readAll(new BufferedReader(new InputStreamReader(
                    connection.getErrorStream() != null ? connection.getErrorStream()
                            : connection.getInputStream(), StandardCharsets.UTF_8)));
            throw new IllegalStateException("Complétion : HTTP " + status
                    + " — " + truncate(body, 200));
        }
        // SSE : lignes "data: {...}" jusqu'au sentinel [DONE].
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8));
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || payload.equals("[DONE]")) {
                continue;
            }
            translateDelta(payload, onEvent, accumulator);
        }
        accumulator.flush(onEvent);
        onEvent.accept(new StreamEvent.Finish("stop"));
    }

    // ---- Analyse SSE (format passerelle) -----------------------------------

    /** Cumul des fragments d'un appel d'outil en cours (index → bâtisseur). */
    static final class ToolCallAccumulator {
        private final List<String> ids = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<StringBuilder> arguments = new ArrayList<>();

        void offer(String id, String name, String argumentsDelta) {
            int index = indexOf(id);
            if (index < 0) {
                if (id != null) {
                    ids.add(id);
                } else {
                    ids.add("appel-" + ids.size());
                }
                names.add(name != null ? name : "");
                arguments.add(new StringBuilder());
                index = ids.size() - 1;
            }
            if (name != null && !name.isEmpty()) {
                names.set(index, name);
            }
            if (argumentsDelta != null) {
                arguments.get(index).append(argumentsDelta);
            }
        }

        private int indexOf(String id) {
            return id != null ? ids.indexOf(id) : -1;
        }

        void flush(java.util.function.Consumer<StreamEvent> onEvent) {
            for (int i = 0; i < ids.size(); i++) {
                onEvent.accept(new StreamEvent.ToolCallEvent(new ToolCall(
                        ids.get(i), names.get(i), arguments.get(i).toString())));
            }
            ids.clear();
            names.clear();
            arguments.clear();
        }
    }

    private static void translateDelta(String payload,
            java.util.function.Consumer<StreamEvent> onEvent,
            ToolCallAccumulator accumulator) {
        // Fragments attendus : {"choices":[{"delta":{"content":"…",
        // "tool_calls":[{"id":"…","function":{"name":"…","arguments":"…"}}]}}]}
        String content = stringAfter(payload, "\"content\":\"");
        if (content != null && !content.isEmpty()) {
            onEvent.accept(new StreamEvent.Text(unescape(content)));
        }
        String toolCallsAt = substringBetween(payload, "\"tool_calls\":[", "]");
        if (toolCallsAt != null) {
            String id = stringAfter(toolCallsAt, "\"id\":\"");
            String name = stringAfter(toolCallsAt, "\"name\":\"");
            String args = stringAfter(toolCallsAt, "\"arguments\":\"");
            accumulator.offer(id, name, args != null ? unescape(args) : null);
        }
        String finish = stringAfter(payload, "\"finish_reason\":\"");
        if (finish != null && !finish.isEmpty() && !"null".equals(finish)) {
            accumulator.flush(onEvent);
        }
    }

    /** Sous-chaîne après un marqueur, jusqu'au guillemet fermant échappé exclu. */
    private static String stringAfter(String body, String marker) {
        int at = body.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int from = at + marker.length();
        int to = body.indexOf('"', from);
        return to < 0 ? null : body.substring(from, to);
    }

    private static String substringBetween(String body, String open, String close) {
        int openAt = body.indexOf(open);
        if (openAt < 0) {
            return null;
        }
        int closeAt = body.lastIndexOf(close);
        if (closeAt <= openAt) {
            return null;
        }
        return body.substring(openAt + open.length(), closeAt);
    }

    /** Échappements JSON minimaux (\\n, \\", \\\\, \\t). */
    static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ---- Requête JSON -------------------------------------------------------

    String requestJson(ChatRequest request) {
        StringBuilder json = new StringBuilder("{\"model\":");
        json.append(quote(request.model()));
        json.append(",\"stream\":true,\"max_tokens\":").append(request.maxTokens());
        json.append(",\"messages\":[");
        for (int i = 0; i < request.messages().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(messageJson(request.messages().get(i)));
        }
        json.append("]");
        if (request.tools() != null && !request.tools().isEmpty()) {
            json.append(",\"tools\":[");
            for (int i = 0; i < request.tools().size(); i++) {
                LlmTransport.ToolSpec tool = request.tools().get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"type\":\"function\",\"function\":{\"name\":")
                        .append(quote(tool.name()))
                        .append(",\"description\":").append(quote(tool.description()))
                        .append("}}");
            }
            json.append("]");
        }
        json.append('}');
        return json.toString();
    }

    private static String messageJson(ChatMessage message) {
        StringBuilder json = new StringBuilder("{\"role\":").append(quote(message.role()));
        if (message.content() != null && !message.content().isEmpty()) {
            json.append(",\"content\":").append(quote(message.content()));
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            json.append(",\"tool_calls\":[");
            for (int i = 0; i < message.toolCalls().size(); i++) {
                ToolCall call = message.toolCalls().get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"id\":").append(quote(call.id()))
                        .append(",\"type\":\"function\",\"function\":{\"name\":")
                        .append(quote(call.name()))
                        .append(",\"arguments\":").append(quote(call.argumentsJson()))
                        .append("}}");
            }
            json.append("]");
        }
        if (message.toolCallId() != null) {
            json.append(",\"tool_call_id\":").append(quote(message.toolCallId()));
        }
        json.append('}');
        return json.toString();
    }

    /** Échappement JSON d'une valeur. */
    static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\t' -> json.append("\\t");
                case '\r' -> json.append("\\r");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    // ---- Connexion ----------------------------------------------------------

    private static URL url(String baseUrl, String path) throws Exception {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return new URL(base + path);
    }

    private static HttpURLConnection open(String method, URL url, String apiKey)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        if (apiKey != null && !apiKey.isBlank()) {
            // La clé part dans l'en-tête uniquement — jamais dans les logs.
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        return connection;
    }

    private static String readAll(BufferedReader reader) throws Exception {
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        return body.toString();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
