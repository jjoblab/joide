package jo.joide.ai;

/**
 * Options de recherche de texte (Lot 3) : la recherche était une simple
 * conteneur de sous-chaîne avec la requête confondue avec le chemin — elle
 * devient une vraie recherche : texte ou regex, casse, mot entier, glob de
 * fichiers, lignes de contexte. Pur, testé.
 */
public record SearchOptions(boolean regex, boolean caseSensitive, boolean wholeWord,
        String globFilter, int contextLines) {

    /** Recherche par défaut : texte brut, insensible à la casse, sans filtre. */
    public static SearchOptions text() {
        return new SearchOptions(false, false, false, null, 0);
    }

    /**
     * Filtre de fichiers « glob » simple : {@code *.java} (suffixe),
     * {@code *.xml}, ou plusieurs séparés par des virgules. Null = tout.
     */
    public boolean matchesFilter(String fileName) {
        if (globFilter == null || globFilter.trim().isEmpty()) {
            return true;
        }
        for (String pattern : globFilter.split(",")) {
            String trimmed = pattern.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("*.")) {
                if (fileName.toLowerCase(java.util.Locale.ROOT)
                        .endsWith(trimmed.substring(1).toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            } else if (trimmed.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    /** Mot entier : le caractère précédent/suivant n'est pas alphanumérique. */
    public static boolean isWholeWord(String line, int start, int end) {
        boolean before = start > 0
                && Character.isLetterOrDigit(line.charAt(start - 1));
        boolean after = end < line.length()
                && Character.isLetterOrDigit(line.charAt(end));
        return !before && !after;
    }
}
