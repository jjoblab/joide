package jo.joide.ai.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jo.joide.ai.json.Json;
import jo.joide.ai.json.JsonException;

/**
 * Catalogue de modèles neutre (Lot 2) : analyse la réponse de
 * {@code GET /models} — le format enrichi d'OpenRouter (prix, capacités,
 * fenêtre de contexte) et le format simple compatible OpenAI (ids seuls).
 * Les valeurs inconnues restent inconnues ({@code -1} / faux) : rien n'est
 * deviné, jamais affiché comme réel.
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    /** Un modèle du catalogue, tel que le fournisseur le décrit. */
    public record ModelInfo(String id, String name, long contextLength,
            double promptPricePerToken, double completionPricePerToken,
            boolean supportsTools, boolean supportsReasoning, boolean supportsVision,
            long maxCompletionTokens, boolean free) {

        /** Prix connus (par token) ? Faux si le catalogue ne les donne pas. */
        public boolean pricingKnown() {
            return promptPricePerToken >= 0 && completionPricePerToken >= 0;
        }

        /** Nom affichable (le nom complet, sinon l'id). */
        public String displayName() {
            return name == null || name.isEmpty() ? id : name;
        }
    }

    /** Analyse un corps /models (format enrichi OpenRouter). */
    public static List<ModelInfo> parse(String body) throws JsonException {
        Map<String, Object> root = Json.parseObject(body);
        List<ModelInfo> models = new ArrayList<>();
        if (!(root.get("data") instanceof List<?> entries)) {
            return models;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> model) || !(model.get("id") instanceof String id)) {
                continue;
            }
            String name = model.get("name") instanceof String value ? value : null;
            long contextLength = longOf(model.get("context_length"), -1);
            double promptPrice = -1;
            double completionPrice = -1;
            if (model.get("pricing") instanceof Map<?, ?> pricing) {
                promptPrice = doubleOf(pricing.get("prompt"), -1);
                completionPrice = doubleOf(pricing.get("completion"), -1);
            }
            boolean tools = false;
            boolean reasoning = false;
            if (model.get("supported_parameters") instanceof List<?> parameters) {
                for (Object parameter : parameters) {
                    if ("tools".equals(parameter)) {
                        tools = true;
                    } else if ("reasoning".equals(parameter)
                            || "include_reasoning".equals(parameter)) {
                        reasoning = true;
                    }
                }
            }
            boolean vision = false;
            if (model.get("architecture") instanceof Map<?, ?> architecture
                    && architecture.get("input_modalities") instanceof List<?> modalities) {
                for (Object modality : modalities) {
                    if ("image".equals(modality)) {
                        vision = true;
                    }
                }
            }
            long maxCompletionTokens = -1;
            if (model.get("top_provider") instanceof Map<?, ?> topProvider
                    && topProvider.get("max_completion_tokens") instanceof Number n) {
                maxCompletionTokens = n.longValue();
            }
            // Variante gratuite : suffixe « :free » du slug (doc OpenRouter),
            // ou prix connus et tous deux nuls.
            boolean free = id.endsWith(":free")
                    || (promptPrice >= 0 && completionPrice >= 0
                            && promptPrice == 0 && completionPrice == 0);
            models.add(new ModelInfo(id, name, contextLength, promptPrice,
                    completionPrice, tools, reasoning, vision, maxCompletionTokens, free));
        }
        return models;
    }

    /** Ids seuls (format simple compatible OpenAI). */
    public static List<String> parseIds(String body) throws JsonException {
        Map<String, Object> root = Json.parseObject(body);
        List<String> ids = new ArrayList<>();
        if (root.get("data") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> model && model.get("id") instanceof String id) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static long longOf(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static double doubleOf(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        // OpenRouter donne les prix en chaînes (« 0.00003 » par token).
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
