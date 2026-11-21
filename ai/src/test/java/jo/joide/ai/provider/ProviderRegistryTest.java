package jo.joide.ai.provider;

import java.util.HashMap;
import java.util.Map;
import jo.joide.ai.LlmTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 2 : registre des fournisseurs — identifiants stables, URLs complètes
 * documentées (chemins selon le fournisseur), réglages par fournisseur,
 * raisonnement sous la forme du fournisseur, migration des identifiants
 * historiques, conversion d'effort.
 */
class ProviderRegistryTest {

    @Test
    void registryListsVerifiedProvidersOpenRouterFirst() {
        assertEquals(4, ProviderRegistry.all().size());
        assertEquals("openrouter", ProviderRegistry.all().get(0).id(),
                "OpenRouter en tête (fournisseur recommandé par défaut)");
        assertTrue(ProviderRegistry.all().stream()
                .allMatch(spec -> ProviderRegistry.byId(spec.id()).isPresent()));
        assertFalse(ProviderRegistry.isKnown("laboratoire"),
                "l'identifiant de maquette a disparu");
        assertFalse(ProviderRegistry.isKnown("passerelle"));
    }

    @Test
    void endpointsFollowEachProviderDocumentedPaths() {
        // OpenRouter : base documentée https://openrouter.ai/api/v1 (doc officielle).
        ProviderSpec or = ProviderSpec.openRouter();
        assertEquals("https://openrouter.ai/api/v1/chat/completions", or.chatUrl(or.defaultBaseUrl()));
        assertEquals("https://openrouter.ai/api/v1/models", or.modelsUrl(or.defaultBaseUrl()));

        // OpenAI : format canonique /v1.
        ProviderSpec openai = ProviderSpec.openAi();
        assertEquals("https://api.openai.com/v1/chat/completions",
                openai.chatUrl(openai.defaultBaseUrl()));

        // Gemini : la couche de compatibilité vit sous /v1beta/openai (doc Google).
        ProviderSpec gemini = ProviderSpec.gemini();
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                gemini.chatUrl(gemini.defaultBaseUrl()));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models",
                gemini.modelsUrl(gemini.defaultBaseUrl()));

        // Passerelle : URL éditable, clé optionnelle.
        ProviderSpec custom = ProviderSpec.custom();
        assertTrue(custom.baseUrlEditable());
        assertTrue(custom.keyOptional());
        assertFalse(or.baseUrlEditable());
        assertFalse(or.keyOptional());
    }

    @Test
    void endpointBuilderUsesOverrideOrDefaultAndToleratesTrailingSlash() {
        ProviderSpec custom = ProviderSpec.custom();
        LlmTransport.Endpoint overridden = custom.endpoint(
                "http://192.168.1.10:8080/v1/", "cle", null);
        assertEquals("http://192.168.1.10:8080/v1/chat/completions", overridden.chatUrl());
        assertEquals("http://192.168.1.10:8080/v1/models", overridden.modelsUrl());
        assertEquals("cle", overridden.apiKey());

        LlmTransport.Endpoint def = custom.endpoint(null, null, null);
        assertEquals("http://localhost:11434/v1/chat/completions", def.chatUrl());
        assertNull(def.apiKey());
    }

    @Test
    void reasoningBodyFollowsProviderFormat() {
        // OpenRouter : objet reasoning (doc officielle).
        Map<String, Object> or = ProviderSpec.openRouter().reasoningBody("high");
        @SuppressWarnings("unchecked")
        Map<String, Object> reasoning = (Map<String, Object>) or.get("reasoning");
        assertEquals("high", reasoning.get("effort"));

        // OpenAI : champ plat.
        assertEquals(java.util.Map.of("reasoning_effort", "low"),
                ProviderSpec.openAi().reasoningBody("low"));

        // Gemini / passerelle : rien tant que non confirmé.
        assertTrue(ProviderSpec.gemini().reasoningBody("high").isEmpty());
        assertTrue(ProviderSpec.custom().reasoningBody("high").isEmpty());

        // Aucun effort : rien, pour tous.
        assertTrue(ProviderSpec.openRouter().reasoningBody(null).isEmpty());
    }

    @Test
    void effortsConvertPersistedIdsToApiValues() {
        assertEquals("none", Efforts.toApi("aucun"));
        assertEquals("low", Efforts.toApi("faible"));
        assertEquals("medium", Efforts.toApi("moyen"));
        assertEquals("high", Efforts.toApi("eleve"));
        assertNull(Efforts.toApi("defaut"), "défaut : on n'envoie rien");
        assertNull(Efforts.toApi(null));
        assertNull(Efforts.toApi("inconnu"));
    }

    @Test
    void migrationCarriesPasserelleOverToCustom() {
        Map<String, String> prefs = new HashMap<>();
        prefs.put("fournisseur", "passerelle");
        prefs.put("modele_passerelle", "modele-local");
        prefs.put("base_url_passerelle", "http://192.168.1.10:8080/v1");

        boolean changed = ProviderMigration.migrateIds(prefs::get, prefs::put);

        assertTrue(changed);
        assertEquals("custom", prefs.get("fournisseur"));
        assertEquals("modele-local", prefs.get("modele_custom"));
        assertEquals("http://192.168.1.10:8080/v1", prefs.get("base_url_custom"));
    }

    @Test
    void migrationNeverOverwritesExistingValues() {
        Map<String, String> prefs = new HashMap<>();
        prefs.put("fournisseur", "openrouter");
        prefs.put("modele_passerelle", "ancien");
        prefs.put("modele_custom", "deja-la");

        boolean changed = ProviderMigration.migrateIds(prefs::get, prefs::put);

        assertFalse(changed, "rien à migrer : fournisseur déjà réel, modèle déjà posé");
        assertEquals("openrouter", prefs.get("fournisseur"));
        assertEquals("deja-la", prefs.get("modele_custom"));
    }

    @Test
    void legacyLaboratoireKeyIsDetectable() {
        assertTrue(ProviderMigration.hasLegacyLaboratoireKey(id -> "cle-ancienne"));
        assertFalse(ProviderMigration.hasLegacyLaboratoireKey(id -> null));
    }
}
