package jo.joide.ai;

import java.util.List;
import java.util.Locale;

/**
 * Fournisseurs IA (lot L7, conception §8) : **un laboratoire + une passerelle
 * compatible OpenAI**. Le modèle choisi est **persisté par fournisseur** ; la
 * liste de modèles est vivante (endpoint `/models`) avec **repli statique** si
 * l'endpoint échoue (réseau absent, clé refusée) — l'utilisateur garde toujours
 * un choix utilisable.
 *
 * <p>La passerelle (base URL configurable) est le moyen natif hors ligne :
 * serveur local sans clé. Aucun fournisseur n'est câblé en dur au transport —
 * le port {@link LlmTransport} est injecté.</p>
 */
public final class AiProvider {

    /** Identification du fournisseur (stable, sauvegardé avec le modèle choisi). */
    public enum Kind { LABORATORY, GATEWAY }

    private final Kind kind;
    private final String id;
    private final String displayName;
    private final String defaultBaseUrl;
    private final List<String> staticModels;
    private final LlmTransport transport;

    private AiProvider(Kind kind, String id, String displayName, String defaultBaseUrl,
            List<String> staticModels, LlmTransport transport) {
        this.kind = kind;
        this.id = id;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.staticModels = List.copyOf(staticModels);
        this.transport = transport;
    }

    /** Le laboratoire natif (HTTPS, clé utilisateur). */
    public static AiProvider laboratory(LlmTransport transport) {
        return new AiProvider(Kind.LABORATORY, "laboratoire", "Laboratoire",
                "https://api.laboratoire.exemple", List.of(
                        "laboratoire-v1", "laboratoire-v1-mini"), transport);
    }

    /** La passerelle compatible OpenAI (base URL de l'utilisateur, souvent sans clé). */
    public static AiProvider gateway(LlmTransport transport) {
        return new AiProvider(Kind.GATEWAY, "passerelle", "Passerelle (compatible OpenAI)",
                "http://localhost:11434", List.of(
                        "modele-local", "modele-local-mini"), transport);
    }

    public Kind kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Base URL par défaut (la passerelle la relit des préférences). */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** Liste statique de repli (toujours disponible, même hors réseau). */
    public List<String> staticModels() {
        return staticModels;
    }

    /**
     * Liste vivante des modèles, avec repli statique : l'échec de l'endpoint
     * (réseau, clé) retombe sur {@link #staticModels()} — jamais d'écran vide.
     *
     * @param baseUrl base effective (préférences : passerelle configurable).
     * @param apiKey  clé (nulle admise pour un serveur local).
     * @return liste des identifiants de modèles affichables.
     */
    public List<String> listModels(String baseUrl, String apiKey) {
        try {
            List<String> live = transport.listModels(baseUrl, apiKey);
            if (live != null && !live.isEmpty()) {
                return live;
            }
        } catch (Exception e) {
            // Repli statique silencieux côté modèle ; l'échec réseau est affiché
            // par ailleurs (jamais une liste vide muette).
        }
        return staticModels;
    }

    /**
     * Exécute une complétion en flux.
     *
     * @param model    modèle persisté POUR CE fournisseur.
     * @param messages historique apparié (appels/résultats complets).
     * @param tools    roster d'outils de la moitié stable.
     * @param onEvent  consommateur de deltas.
     */
    public void chat(String model, String baseUrl, String apiKey,
            List<LlmTransport.ChatMessage> messages,
            List<LlmTransport.ToolSpec> tools,
            java.util.function.Consumer<LlmTransport.StreamEvent> onEvent)
            throws Exception {
        transport.chat(baseUrl, apiKey,
                new LlmTransport.ChatRequest(model, messages, tools, 4096), onEvent);
    }

    /** Description honnête pour l'écran de réglages (ce qui part de l'appareil). */
    public String privacyNotice(Locale locale) {
        return "La conversation complète (messages, résultats d'outils, contenus de fichiers lus) part chez le fournisseur.";
    }
}
