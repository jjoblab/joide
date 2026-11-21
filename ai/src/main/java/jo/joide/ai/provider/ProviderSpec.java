package jo.joide.ai.provider;

import java.util.Collections;
import jo.joide.ai.LlmTransport;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Description déclarative d'un fournisseur (Lot 2) : id stable, nom affiché,
 * URLs par défaut (chat et modèles — les chemins varient : la couche de
 * compatibilité OpenAI de Google vit sous {@code /v1beta/openai/}, les autres
 * sous {@code /v1/}), auth, format du raisonnement, en-têtes d'attribution.
 *
 * <p>Chaque URL constante ci-dessous vient de la documentation officielle du
 * fournisseur (consignée dans l'ADR du Lot 2) : rien n'est deviné. Les
 * fournisseurs non vérifiés ne sont pas dans le registre.</p>
 */
public final class ProviderSpec {

    /** Comment l'effort de raisonnement part au fournisseur. */
    public enum ReasoningFormat {
        /** Objet {@code reasoning: {effort}} (OpenRouter — doc officielle). */
        REASONING_OBJECT,
        /** Champ plat {@code reasoning_effort} (OpenAI). */
        EFFORT_FIELD,
        /** Non confirmé pour ce fournisseur : aucun champ envoyé. */
        NONE
    }

    private final String id;
    private final String displayName;
    private final String defaultBaseUrl;
    private final boolean baseUrlEditable;
    private final boolean keyOptional;
    private final ReasoningFormat reasoningFormat;
    private final boolean attributionHeaders;
    private final String source;

    private ProviderSpec(String id, String displayName, String defaultBaseUrl,
            boolean baseUrlEditable, boolean keyOptional, ReasoningFormat reasoningFormat,
            boolean attributionHeaders, String source) {
        this.id = id;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.baseUrlEditable = baseUrlEditable;
        this.keyOptional = keyOptional;
        this.reasoningFormat = reasoningFormat;
        this.attributionHeaders = attributionHeaders;
        this.source = source;
    }

    /** OpenRouter — fournisseur recommandé (doc : openrouter.ai/docs). */
    public static ProviderSpec openRouter() {
        return new ProviderSpec("openrouter", "OpenRouter",
                "https://openrouter.ai/api/v1", false, false,
                ReasoningFormat.REASONING_OBJECT, true,
                "https://openrouter.ai/docs/api-reference/overview");
    }

    /** OpenAI — format canonique des points de terminaison utilisés. */
    public static ProviderSpec openAi() {
        return new ProviderSpec("openai", "OpenAI",
                "https://api.openai.com/v1", false, false,
                ReasoningFormat.EFFORT_FIELD, false,
                "https://platform.openai.com/docs/api-reference/chat");
    }

    /** Google Gemini — couche de compatibilité OpenAI (doc officielle vérifiée). */
    public static ProviderSpec gemini() {
        return new ProviderSpec("gemini", "Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta/openai", false, false,
                ReasoningFormat.NONE, false,
                "https://aistudio.google.com/docs/openai");
    }

    /** Passerelle personnalisable compatible OpenAI (Ollama, LM Studio…), URL éditable, clé optionnelle. */
    public static ProviderSpec custom() {
        return new ProviderSpec("custom", "Passerelle personnalisable",
                "http://localhost:11434/v1", true, true,
                ReasoningFormat.NONE, false,
                "URL choisie par l'utilisateur (serveur compatible OpenAI local ou distant)");
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Base par défaut (source documentée dans {@link #source()}) ; sans barre finale. */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** Vrai si l'utilisateur peut éditer l'URL de base (passerelle personnalisable). */
    public boolean baseUrlEditable() {
        return baseUrlEditable;
    }

    /** Vrai si la clé est optionnelle (serveur local sans auth). */
    public boolean keyOptional() {
        return keyOptional;
    }

    public ReasoningFormat reasoningFormat() {
        return reasoningFormat;
    }

    /** Vrai si le fournisseur accepte les en-têtes d'attribution de l'application. */
    public boolean attributionHeaders() {
        return attributionHeaders;
    }

    /** Source documentée de la base URL (à citer dans l'ADR). */
    public String source() {
        return source;
    }

    /** URL du chat completions pour cette base (barre finale tolérée). */
    public String chatUrl(String baseUrl) {
        return join(baseUrl) + "/chat/completions";
    }

    /** URL de la liste des modèles pour cette base. */
    public String modelsUrl(String baseUrl) {
        return join(baseUrl) + "/models";
    }

    /**
     * Point de terminaison complet pour le transport : la base effective est
     * celle donnée (passerelle personnalisable) ou la base par défaut du
     * fournisseur ; les en-têtes additionnels (attribution) sont optionnels.
     */
    public LlmTransport.Endpoint endpoint(String baseUrl, String apiKey,
            Map<String, String> headers) {
        String effective = baseUrl == null || baseUrl.isEmpty() ? defaultBaseUrl : baseUrl;
        Map<String, String> safeHeaders = headers == null
                ? Collections.emptyMap() : headers;
        return new LlmTransport.Endpoint(chatUrl(effective), modelsUrl(effective),
                apiKey, safeHeaders);
    }

    private static String join(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * Champs de corps propres au raisonnement pour ce fournisseur (effort de
     * JoIDE déjà converti en valeur d'API). Peut être vide : jamais de champ
     * non confirmé.
     */
    public Map<String, Object> reasoningBody(String apiEffort) {
        if (apiEffort == null || apiEffort.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        switch (reasoningFormat) {
            case REASONING_OBJECT -> {
                Map<String, Object> reasoning = new LinkedHashMap<>();
                reasoning.put("effort", apiEffort);
                body.put("reasoning", reasoning);
            }
            case EFFORT_FIELD -> body.put("reasoning_effort", apiEffort);
            case NONE -> {
                return Collections.emptyMap();
            }
        }
        return body;
    }

    /** Représentation lisible (nom + id, jamais de secret). */
    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}
