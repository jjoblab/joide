package jo.joide.ai.provider;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 2 : catalogue de modèles (format enrichi OpenRouter — prix par token en
 * chaînes, capacités via supported_parameters et input_modalities, variante
 * :free) et options de routage OpenRouter.
 */
class ModelCatalogTest {

    private static final String OPENROUTER_SAMPLE = """
            {"data":[
              {"id":"exemple/a-outils","name":"A Outils","context_length":128000,
               "pricing":{"prompt":"0.000003","completion":"0.000009"},
               "supported_parameters":["temperature","tools","reasoning"],
               "architecture":{"input_modalities":["text","image"]},
               "top_provider":{"max_completion_tokens":8192}},
              {"id":"exemple/b-gratuit","name":"B Gratuit","context_length":32000,
               "pricing":{"prompt":"0","completion":"0"},
               "supported_parameters":["temperature","max_tokens"],
               "architecture":{"input_modalities":["text"]}},
              {"id":"exemple/c-simple","context_length":8000}
            ]}""";

    @Test
    void parsesEnrichedCatalogueWithRealCapacities() throws Exception {
        List<ModelCatalog.ModelInfo> models = ModelCatalog.parse(OPENROUTER_SAMPLE);

        assertEquals(3, models.size());
        ModelCatalog.ModelInfo a = models.get(0);
        assertEquals("exemple/a-outils", a.id());
        assertEquals("A Outils", a.displayName());
        assertEquals(128000, a.contextLength());
        assertTrue(a.supportsTools(), "tools dans supported_parameters");
        assertTrue(a.supportsReasoning(), "reasoning dans supported_parameters");
        assertTrue(a.supportsVision(), "image dans input_modalities");
        assertEquals(8192, a.maxCompletionTokens());
        assertTrue(a.pricingKnown());
        assertEquals(0.000003, a.promptPricePerToken(), 1e-9, "prix en chaîne, par token");
        assertEquals(0.000009, a.completionPricePerToken(), 1e-9);

        ModelCatalog.ModelInfo b = models.get(1);
        assertTrue(b.free(), "prix nuls : gratuit");
        assertTrue(b.pricingKnown());
        assertTrue(!b.supportsTools() && !b.supportsReasoning() && !b.supportsVision(),
                "capacités absentes : non supportées, pas devinées");

        ModelCatalog.ModelInfo c = models.get(2);
        assertTrue(!c.pricingKnown() && c.contextLength() == 8000,
                "champs absents : inconnus, jamais inventés");
        assertEquals("exemple/c-simple", c.displayName(), "sans nom : l'id sert de nom");
    }

    @Test
    void parsesSimpleOpenAiCompatibleIds() throws Exception {
        List<String> ids = ModelCatalog.parseIds(
                "{\"data\":[{\"id\":\"m-a\"},{\"id\":\"m-b\"},{\"other\":true}]}");
        assertEquals(List.of("m-a", "m-b"), ids);
    }

    @Test
    void freeSuffixMarksFreeVariants() throws Exception {
        List<ModelCatalog.ModelInfo> models = ModelCatalog.parse(
                "{\"data\":[{\"id\":\"exemple/x:free\",\"pricing\":{\"prompt\":\"0.000001\","
                        + "\"completion\":\"0.000001\"}}]}");
        assertTrue(models.get(0).free(), "suffixe :free = variante gratuite (doc OpenRouter)");
    }

    @Test
    void invalidJsonFailsClearly() {
        assertThrows(jo.joide.ai.json.JsonException.class,
                () -> ModelCatalog.parse("{\"data\":"));
    }

    @Test
    void routingBodyOnlyCarriesSetFields() {
        Map<String, Object> empty = OpenRouterOptions.body(
                new OpenRouterOptions.Routing(null, null, null, null, null));
        assertTrue(empty.isEmpty(), "aucun réglage : aucun champ envoyé");

        Map<String, Object> full = OpenRouterOptions.body(
                new OpenRouterOptions.Routing("price", false, true, "deny",
                        List.of("exemple/a", "exemple/b")));
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = (Map<String, Object>) full.get("provider");
        assertEquals("price", provider.get("sort"));
        assertEquals(Boolean.FALSE, provider.get("allow_fallbacks"));
        assertEquals(Boolean.TRUE, provider.get("require_parameters"));
        assertEquals("deny", provider.get("data_collection"));
        assertEquals(List.of("exemple/a", "exemple/b"), full.get("models"));
    }
}
