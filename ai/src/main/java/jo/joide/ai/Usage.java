package jo.joide.ai;

/**
 * Usage d'un tour (Lot 1) : tokens d'entrée/sortie/raisonnement et cache,
 * signalés par le fournisseur en fin de flux (valeurs réelles, jamais
 * estimées). Le coût estimé vit côté fournisseur (prix connu) ou chez l'hôte.
 */
public record Usage(long promptTokens, long completionTokens, long reasoningTokens,
        long cachedPromptTokens) {

    /** Usage inconnu (le fournisseur n'a rien signalé). */
    public static Usage unknown() {
        return new Usage(-1, -1, -1, -1);
    }

    /** Usage minimal (entrée/sortie seules). */
    public static Usage of(long promptTokens, long completionTokens) {
        return new Usage(promptTokens, completionTokens, 0, 0);
    }

    /** Vrai si le fournisseur n'a pas signalé d'usage. */
    public boolean isUnknown() {
        return promptTokens < 0 || completionTokens < 0;
    }

    /** Total des tokens consommés (0 si inconnu). */
    public long totalTokens() {
        return isUnknown() ? 0 : promptTokens + completionTokens;
    }
}
