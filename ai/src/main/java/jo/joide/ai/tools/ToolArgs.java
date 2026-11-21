package jo.joide.ai.tools;

import java.util.Map;
import jo.joide.ai.json.Json;
import jo.joide.ai.json.JsonException;

/**
 * Accès typé aux arguments d'outil (Lot 3) : remplace les extractions par
 * {@code indexOf} qui coupaient le contenu au premier guillemet échappé et
 * cassaient sur les sauts de ligne. Les arguments sont analysés par le codec
 * JSON ; une erreur d'argument devient un message **renvoyé au modèle** avec
 * le schéma attendu — pas une exception muette.
 */
public final class ToolArgs {

    private final Map<String, Object> values;

    private ToolArgs(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * Analyse les arguments du modèle ; une erreur (JSON invalide, racine non
     * objet) devient une {@link BadArguments} lisible pour le modèle.
     */
    public static ToolArgs of(String argumentsJson) throws BadArguments {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return new ToolArgs(Map.of());
        }
        try {
            return new ToolArgs(Json.parseObject(argumentsJson));
        } catch (JsonException e) {
            throw new BadArguments("Arguments invalides (JSON) : " + e.getMessage()
                    + ". Renvoie un objet JSON conforme au schéma de l'outil.");
        }
    }

    /** Chaîne obligatoire ; erreur pour le modèle si absente. */
    public String string(String key) throws BadArguments {
        Object value = values.get(key);
        if (value instanceof String text && !text.isEmpty()) {
            return text;
        }
        throw missing(key, "chaîne non vide");
    }

    /** Chaîne optionnelle (valeur par défaut si absente ou vide). */
    public String stringOr(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isEmpty() ? text : fallback;
    }

    /** Entier optionnel (défaut si absent ou non entier). */
    public int intOr(String key, int fallback) {
        return values.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    /** Booléen optionnel (défaut si absent ou non booléen). */
    public boolean boolOr(String key, boolean fallback) {
        return values.get(key) instanceof Boolean b ? b : fallback;
    }

    /** Valeur brute d'un argument (List/Map/String/Number/Boolean/null). */
    public Object raw(String key) {
        return values.get(key);
    }

    private BadArguments missing(String key, String expected) {
        return new BadArguments("Argument « " + key + " » manquant ou invalide ("
                + expected + "). Vérifie le schéma de l'outil et renvoie l'appel corrigé.");
    }

    /** Erreur d'arguments : le message part au modèle tel quel. */
    public static final class BadArguments extends Exception {

        BadArguments(String message) {
            super(message);
        }
    }
}
