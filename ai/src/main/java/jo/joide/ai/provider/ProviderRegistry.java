package jo.joide.ai.provider;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Registre des fournisseurs (Lot 2) : remplace les deux identifiants fictifs
 * (« laboratoire », « passerelle ») par des fournisseurs réels, chacun vérifié
 * dans sa documentation officielle avant d'entrer ici (source citée dans le
 * {@link ProviderSpec} et l'ADR).
 *
 * <p><b>Anthropic n'est pas encore au registre</b> : sa documentation officielle
 * n'est pas accessible depuis l'environnement de développement (blocage par
 * région) et les sources tierces se contredisent sur l'endpoint de
 * compatibilité — la règle du dépôt interdit d'envoyer un champ non confirmé.
 * L'adaptateur arrivera après vérification (consigné dans
 * {@code docs/limites-et-suite.md}).</p>
 */
public final class ProviderRegistry {

    private static final List<ProviderSpec> PROVIDERS = Arrays.asList(
            ProviderSpec.openRouter(),
            ProviderSpec.openAi(),
            ProviderSpec.gemini(),
            ProviderSpec.custom());

    private ProviderRegistry() {}

    /** Fournisseurs connus, OpenRouter en tête (recommandé par défaut). */
    public static List<ProviderSpec> all() {
        return PROVIDERS;
    }

    /** Recherche par id ; vide si inconnu (jamais de fournisseur deviné). */
    public static Optional<ProviderSpec> byId(String id) {
        for (ProviderSpec spec : PROVIDERS) {
            if (spec.id().equals(id)) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    /** Fournisseur par défaut (premier du registre : OpenRouter). */
    public static ProviderSpec defaultProvider() {
        return PROVIDERS.get(0);
    }

    /** Vrai si l'id est un identifiant connu du registre. */
    public static boolean isKnown(String id) {
        return byId(id).isPresent();
    }
}
