package jo.joide.ai.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Options de requête propres à OpenRouter (Lot 2) : routage des fournisseurs
 * et modèles de repli. Chaque champ vient de la documentation officielle
 * (guides/providers — page « Provider Routing ») : l'objet {@code provider}
 * ({@code sort}, {@code allow_fallbacks}, {@code require_parameters},
 * {@code data_collection}) et le tableau {@code models} (replis, dans
 * l'ordre). Rien n'est envoyé qui ne soit réglé.
 */
public final class OpenRouterOptions {

    private OpenRouterOptions() {}

    /** Réglages de routage persistés (clés de joide_reglages_ia, préfixe or_). */
    public record Routing(String sort, Boolean allowFallbacks, Boolean requireParameters,
            String dataCollection, List<String> fallbackModels) {

        /** Aucun réglage posé ? Aucun champ envoyé (routage par défaut du fournisseur). */
        public boolean isEmpty() {
            return (sort == null || sort.isEmpty()) && allowFallbacks == null
                    && requireParameters == null
                    && (dataCollection == null || dataCollection.isEmpty())
                    && (fallbackModels == null || fallbackModels.isEmpty());
        }
    }

    /**
     * Corps à fusionner dans la requête : {@code provider} (routage) et
     * {@code models} (replis). Vide si aucun réglage.
     */
    public static Map<String, Object> body(Routing routing) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (routing == null || routing.isEmpty()) {
            return body;
        }
        Map<String, Object> provider = new LinkedHashMap<>();
        if (routing.sort() != null && !routing.sort().isEmpty()) {
            provider.put("sort", routing.sort());
        }
        if (routing.allowFallbacks() != null) {
            provider.put("allow_fallbacks", routing.allowFallbacks());
        }
        if (routing.requireParameters() != null) {
            provider.put("require_parameters", routing.requireParameters());
        }
        if (routing.dataCollection() != null && !routing.dataCollection().isEmpty()) {
            provider.put("data_collection", routing.dataCollection());
        }
        if (!provider.isEmpty()) {
            body.put("provider", provider);
        }
        if (routing.fallbackModels() != null && !routing.fallbackModels().isEmpty()) {
            body.put("models", new ArrayList<>(routing.fallbackModels()));
        }
        return body;
    }
}
