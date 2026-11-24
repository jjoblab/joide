package jo.joide.diag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expurgation des textes destinés au journal et aux rapports de plantage
 * (portage CodeIDE règle 15, ADR-021) : <b>aucune donnée personnelle</b> dans
 * ce qui est écrit sur disque. Appliquée <b>à l'écriture</b> (pipeline), pas
 * seulement à l'export — ce qui est sur disque est déjà propre.
 *
 * <p>Trois familles de données sont masquées :</p>
 * <ul>
 *   <li>adresses e-mail → {@code <courriel>} ;</li>
 *   <li>URI {@code content://} : l'<b>autorité est conservée</b> (utile au
 *       diagnostic, elle identifie le fournisseur SAF) et l'identifiant du
 *       document est <b>haché</b> sur 8 caractères hexadécimaux — deux URI
 *       identiques produisent le même hachage (corrélables), deux URI
 *       différentes restent masquées ;</li>
 *   <li>chemins absolus et URI {@code file://} → {@code <chemin>}.</li>
 * </ul>
 *
 * <p>Secrets (portage ADR 0124 de CodeIDE §3) : en-têtes
 * {@code Authorization}/{@code Bearer}/{@code x-api-key}, clés
 * {@code sk-…} (fournisseurs), clés Google {@code AIza…} →
 * {@code <secret>}. S'ajoute l'amélioration JoIDE : jetons GitHub
 * {@code ghp_…}/{@code gho_…} et les longs hexadécimaux (32+) des jetons
 * d'API — le dépôt manipule des PAT, ils ne doivent jamais fuir dans le
 * journal.</p>
 *
 * <p>L'expurgation est <b>idempotente</b> — réexpurger un texte déjà masqué
 * ne le déforme pas (le hachage d'une URI déjà hachée est reconnu et conservé
 * tel quel). Limites assumées : un chemin contenant des espaces n'est masqué
 * que jusqu'au premier espace ; les URI http(s) ne sont pas masquées (elles
 * ne portent aucune donnée locale).</p>
 */
public final class LogRedactor {

    private static final String MASK_EMAIL = "<courriel>";
    private static final String MASK_PATH = "<chemin>";
    private static final String MASK_SECRET = "<secret>";
    private static final String HASH_PREFIX = "/h-";

    /** Nombre de caractères hexadécimaux de l'identifiant haché. */
    private static final int HASH_SIZE = 8;

    private static final Pattern REGEX_EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** Autorité = premier segment ; reste = identifiant du document. */
    private static final Pattern REGEX_CONTENT_URI =
            Pattern.compile("\\bcontent://([^/\\s\"'()<>]+)([^\\s\"'()<>]*)");

    private static final Pattern REGEX_FILE_URI =
            Pattern.compile("\\bfile://[^\\s\"'()<>]+");

    /**
     * Chemin absolu : un {@code /} initial non précédé d'un caractère de mot,
     * de {@code :} ou de {@code /} (ce qui épargne les URI http(s) déjà
     * formées), suivi de segments.
     */
    private static final Pattern REGEX_ABSOLUTE_PATH =
            Pattern.compile("(?<![\\w:/])/[\\w.@+-]+(?:/[\\w.@+-]+)*/?");

    /** Trace d'une URI {@code content://} déjà expurgée (idempotence). */
    private static final Pattern REGEX_HASH =
            Pattern.compile("/h-[0-9a-f]{" + HASH_SIZE + "}");

    // ----- Secrets ------------------------------------------------------------

    /**
     * En-tête {@code Authorization} : schéma + identifiant (« Bearer xyz »,
     * « Basic xyz »…) masqués ensemble — masquer seulement le schéma
     * laisserait deviner le type de jeton.
     */
    private static final Pattern REGEX_AUTHORIZATION = Pattern.compile(
            "\\bAuthorization\\s*[:=]\\s*\\S+(\\s+\\S+)?", Pattern.CASE_INSENSITIVE);

    /** Jeton {@code Bearer} hors en-tête Authorization (déjà masqué avant). */
    private static final Pattern REGEX_BEARER =
            Pattern.compile("\\bBearer\\s+\\S+", Pattern.CASE_INSENSITIVE);

    /** Clé d'API en en-tête ou paramètre ({@code x-api-key: …}). */
    private static final Pattern REGEX_API_KEY = Pattern.compile(
            "\\bx-api-key\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE);

    /** Clés de fournisseurs « sk- » (16 caractères et plus). */
    private static final Pattern REGEX_SK_KEY =
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}");

    /** Clés Google/AI Studio ({@code AIza…}, 20 caractères et plus). */
    private static final Pattern REGEX_AIZA_KEY =
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{20,}");

    /** Jetons GitHub classiques et OAuth (ghp_/gho_/ghu_/ghs_/ghr_ + 30 alphanumériques). */
    private static final Pattern REGEX_GH_TOKEN =
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}");

    private LogRedactor() {
        // Fonctions pures — jamais instanciée.
    }

    /**
     * Expurge un texte en une passe.
     *
     * @param text texte brut produit par le code appelant (message de
     *        journal, message d'exception…)
     * @return le texte masqué, prêt à être persisté ou affiché
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String out = REGEX_EMAIL.matcher(text).replaceAll(MASK_EMAIL);
        out = redactContentUris(out);
        out = REGEX_FILE_URI.matcher(out).replaceAll("file://" + MASK_PATH);
        out = REGEX_ABSOLUTE_PATH.matcher(out).replaceAll(MASK_PATH);
        out = REGEX_AUTHORIZATION.matcher(out).replaceAll(MASK_SECRET);
        out = REGEX_BEARER.matcher(out).replaceAll(MASK_SECRET);
        out = REGEX_API_KEY.matcher(out).replaceAll(MASK_SECRET);
        out = REGEX_SK_KEY.matcher(out).replaceAll(MASK_SECRET);
        out = REGEX_AIZA_KEY.matcher(out).replaceAll(MASK_SECRET);
        out = REGEX_GH_TOKEN.matcher(out).replaceAll(MASK_SECRET);
        return out;
    }

    /**
     * Remplace chaque URI {@code content://} par sa forme autorité +
     * identifiant haché, sauf si elle est déjà expurgée (idempotence).
     */
    private static String redactContentUris(String text) {
        Matcher matcher = REGEX_CONTENT_URI.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String authority = matcher.group(1);
            String rest = matcher.group(2);
            if (rest == null || rest.isEmpty()) {
                out.append(matcher.group());
            } else if (REGEX_HASH.matcher(rest).matches()) {
                out.append(matcher.group());
            } else {
                out.append("content://").append(authority)
                        .append(HASH_PREFIX).append(hash(rest));
            }
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /**
     * Hache un identifiant en SHA-256 tronqué, hexadécimal — déterministe
     * (même entrée, même sortie) mais non réversible de fait.
     */
    private static String hash(String identifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(identifier.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(HASH_SIZE);
            for (int i = 0; i < HASH_SIZE / 2; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 est garanti présent sur toute JVM.
            return "00000000";
        }
    }
}
