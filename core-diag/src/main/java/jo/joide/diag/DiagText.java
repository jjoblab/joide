package jo.joide.diag;

/**
 * Petits outils de texte du diagnostic (ADR-021) : troncature <b>sûre</b> en
 * octets UTF-8 (jamais au milieu d'un caractère multi-octets), première ligne.
 */
public final class DiagText {

    private static final int CONTINUATION_MASK = 0xC0;
    private static final int CONTINUATION_BYTE = 0x80;

    private DiagText() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Tronque un texte à {@code limit} octets UTF-8 <b>sans couper au milieu
     * d'un caractère multi-octets</b> : la coupe recule tant que l'octet exclu
     * amorce une suite (un caractère gardé n'est jamais amputé de ses octets
     * de continuation).
     *
     * @param texte texte à tronquer (null rend chaîne vide)
     * @param limit taille maximale en octets UTF-8
     * @return le texte tronqué, éventuellement vide
     */
    public static String truncateUtf8(String texte, int limit) {
        if (texte == null) {
            return "";
        }
        byte[] bytes = texte.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= limit) {
            return texte;
        }
        int end = limit;
        while (end > 0 && (bytes[end] & CONTINUATION_MASK) == CONTINUATION_BYTE) {
            end--;
        }
        return end <= 0 ? "" : new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * La première ligne d'un texte (null-safe, bornée) — sert aux messages
     * courts des résumés.
     */
    public static String firstLine(String texte, int limit) {
        if (texte == null) {
            return "";
        }
        int cut = texte.indexOf('\n');
        String line = cut >= 0 ? texte.substring(0, cut) : texte;
        line = line.strip();
        return line.isEmpty() ? "" : truncateUtf8(line, limit);
    }
}
