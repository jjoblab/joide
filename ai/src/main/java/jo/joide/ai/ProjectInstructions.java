package jo.joide.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instructions du projet (Lot 3) : lit {@code AGENTS.md} puis {@code CLAUDE.md}
 * à la racine du projet (premier trouvé), **borné à 16 Ko**, **mis en cache
 * par (chemin, mtime)** — le préfixe stable du prompt ne dérive que si le
 * fichier change vraiment (le cache du fournisseur survit). Appelé hors fil
 * UI par l'hôte. Pur, testable.
 */
public final class ProjectInstructions {

    /** Plafond de lecture (16 Ko — REF-111 : instructions bornées). */
    static final int MAX_BYTES = 16 * 1024;

    /** Cache : chemin → (mtime du fichier, contenu chargé). */
    private static final Map<Path, CacheEntry> CACHE = new LinkedHashMap<>();

    private ProjectInstructions() {}

    /**
     * Instructions du projet (avec cache par mtime) ; chaîne vide si aucun
     * fichier d'instructions n'existe. Premier trouvé l'emporte :
     * {@code AGENTS.md}, puis {@code CLAUDE.md}.
     */
    public static String cached(Path projectRoot) {
        if (projectRoot == null) {
            return "";
        }
        for (String name : new String[] {"AGENTS.md", "CLAUDE.md"}) {
            Path file = projectRoot.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                CacheEntry entry = CACHE.get(file);
                if (entry != null && entry.mtime == mtime) {
                    return entry.content;
                }
                String content = readBounded(file);
                CACHE.put(file, new CacheEntry(mtime, content));
                return content;
            } catch (IOException e) {
                // Illisible : aucune instruction (honnête), jamais un plantage.
                return "";
            }
        }
        return "";
    }

    /** Lecture bornée (16 Ko, coupe à un saut de ligne si possible). */
    private static String readBounded(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int limit = Math.min(bytes.length, MAX_BYTES);
        String content = new String(bytes, 0, limit, StandardCharsets.UTF_8);
        if (limit < bytes.length) {
            int lastBreak = content.lastIndexOf('\n');
            content = (lastBreak > 0 ? content.substring(0, lastBreak) : content)
                    + "\n… (instructions tronquées à 16 Ko)";
        }
        return content;
    }

    /** Entrée de cache (mtime + contenu). */
    private record CacheEntry(long mtime, String content) {}
}
