package jo.joide.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rédaction des secrets (Lot 1) : une clé ne sort JAMAIS — ni dans un
 * message d'erreur, ni dans un journal, ni dans un corps HTTP affiché. Le
 * filtre central masque les clés par motif connu (OpenRouter, OpenAI,
 * GitHub, AWS…) et tout identifiant fourni explicitement. Java 8 strict
 * (API 28 : pas de {@code Matcher.replaceAll(Function)}).
 */
public final class Secrets {

    private Secrets() {}

    /**
     * Motifs des identifiants courants (préfixes publics documentés des
     * fournisseurs — pas des secrets en soi, juste leur forme).
     */
    private static final Pattern[] PATTERNS = {
        // Clés style fournisseur : préfixe + caractères restants.
        Pattern.compile("sk-or-v1-[A-Za-z0-9_-]{8,}"),
        Pattern.compile("sk-[A-Za-z0-9_-]{16,}"),
        Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
        Pattern.compile("AKIA[0-9A-Z]{12,}"),
        Pattern.compile("xox[bpars]-[A-Za-z0-9-]{10,}"),
        // Identifiants dans une URL (https://utilisateur:motdepasse@…).
        Pattern.compile("https?://[^/\\s:@]+:[^@\\s]+@")
    };

    /** Remplace tout secret reconnaissable par un masque. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = text;
        for (int i = 0; i < PATTERNS.length; i++) {
            redacted = replaceAllSafe(redacted, PATTERNS[i], i == PATTERNS.length - 1);
        }
        return redacted;
    }

    /** Remplacement Java 8 (appendReplacement + appendTail). */
    private static String replaceAllSafe(String text, Pattern pattern, boolean isUrlPattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String replacement = isUrlPattern
                    ? keepUrlCredentialsMasked(matcher.group(0))
                    : mask(matcher.group(0));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** « https://hôte:••••@ » : garde le schéma et l'hôte, masque le mot de passe. */
    private static String keepUrlCredentialsMasked(String url) {
        int authority = url.indexOf("//") + 2;
        int colon = url.indexOf(':', authority);
        if (colon < 0) {
            return mask(url);
        }
        return url.substring(0, colon) + ":••••@";
    }

    /** Masque une valeur explicite (la clé du transport) partout où elle apparaît. */
    public static String redact(String text, String explicitSecret) {
        String redacted = redact(text);
        if (explicitSecret == null || explicitSecret.isEmpty()
                || explicitSecret.length() < 4 || !redacted.contains(explicitSecret)) {
            return redacted;
        }
        return redacted.replace(explicitSecret, mask(explicitSecret));
    }

    /** Masque lisible : préfixe court + billes. */
    private static String mask(String secret) {
        String prefix = secret.length() <= 8 ? "" : secret.substring(0, Math.min(5, secret.length()));
        return prefix + "••••••";
    }
}
