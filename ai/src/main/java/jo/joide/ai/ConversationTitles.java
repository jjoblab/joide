package jo.joide.ai;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Titre de conversation et nom de projet dérivés du premier message (Lot 0) :
 * le titre de la liste est le début de la première ligne du premier message ;
 * le nom proposé par « Créer un projet depuis cette conversation » en est une
 * forme CamelCase nettoyée (sans diacritiques — les noms deviennent des
 * dossiers). Fonctions pures, testées sur JVM.
 */
public final class ConversationTitles {

    /** Longueur maximale du titre de liste (au-delà : élision honnête). */
    private static final int TITLE_MAX = 48;

    /** Mots purement grammaticaux, jamais utiles dans un nom de projet. */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "le", "la", "les", "un", "une", "des", "du", "de", "d", "l",
            "et", "ou", "a", "au", "aux", "en", "dans", "sur", "sous", "par",
            "pour", "avec", "sans", "est", "sont", "etre", "avoir", "fait",
            "veux", "voudrais", "aimerais", "souhaite",
            "peux", "peut", "peuvent",
            "je", "tu", "te", "toi", "me", "moi", "on", "nous", "vous",
            "il", "elle", "ils", "elles", "ce", "cet", "cette", "ces",
            "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
            "notre", "nos", "votre", "vos", "leur", "leurs",
            "y", "ne", "pas", "plus", "moins", "tres", "bien", "mal",
            "que", "quoi", "qui", "quel", "quelle", "quels", "quelles",
            "comment", "pourquoi", "quand", "ou",
            "bonjour", "salut", "hello", "merci", "stp", "svp"));

    /** Nombre maximal de mots repris dans le nom de projet. */
    private static final int NAME_MAX_WORDS = 3;

    /** Longueur maximale du nom de projet proposé. */
    private static final int NAME_MAX_CHARS = 24;

    private ConversationTitles() {}

    /**
     * Titre de liste : première ligne du message, bornée à {@value TITLE_MAX}
     * caractères (ellipse à la fin), vide si le message est vide.
     */
    public static String deriveTitle(String firstMessage) {
        if (firstMessage == null) {
            return "";
        }
        String line = firstMessage.trim();
        int cut = line.indexOf('\n');
        if (cut >= 0) {
            line = line.substring(0, cut).trim();
        }
        if (line.isEmpty()) {
            return "";
        }
        if (line.length() <= TITLE_MAX) {
            return line;
        }
        return line.substring(0, TITLE_MAX).trim() + "…";
    }

    /**
     * Nom de projet suggéré : CamelCase des premiers mots significatifs
     * (sans mots grammaticaux, sans diacritiques) ; chaîne vide si le
     * message ne fournit rien d'utilisable (l'appelant garde son défaut).
     */
    public static String suggestProjectName(String firstMessage) {
        if (firstMessage == null) {
            return "";
        }
        StringBuilder name = new StringBuilder();
        int words = 0;
        for (String word : firstMessage.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty() || words >= NAME_MAX_WORDS || name.length() >= NAME_MAX_CHARS) {
                break;
            }
            String clean = stripDiacritics(word).toLowerCase(Locale.ROOT);
            if (clean.length() < 3 || STOP_WORDS.contains(clean)) {
                continue;
            }
            name.append(Character.toUpperCase(clean.charAt(0)));
            if (clean.length() > 1) {
                name.append(clean.substring(1));
            }
            words++;
        }
        return name.toString();
    }

    /** Retire les diacritiques (é→e) — les noms deviennent des dossiers. */
    private static String stripDiacritics(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
}
