package jo.joide.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Port espace de travail étroit de l'agent (conception §8, REF-114) : **aucun
 * outil ne touche le projet directement** — tout passe par cette interface,
 * implémentée par l'hôte sur le dossier du projet (et par un fake en mémoire
 * dans les tests). Les lectures bornent leurs résultats **à l'arrivée** (avec
 * compte de ce qui manque — REF-113) : borner à la source plutôt que réécrire
 * ensuite préserve le cache de prompt.
 *
 * <p>Les outils de lecture ne sont jamais permissionnés ; les mutations passent
 * la porte {@code PermissionGate} avant d'atteindre le port.</p>
 */
public interface AgentWorkspace {

    /** Résultat borné à l'arrivée : contenu + compte de ce qui manque. */
    record Bounded(String content, int omitted) {

        /** Résultat complet. */
        public static Bounded full(String content) {
            return new Bounded(content, 0);
        }
    }

    /** Fichier lisible dans une fenêtre de lignes (bornage à l'arrivée). */
    Bounded readFile(String relativePath, int firstLine, int maxLines) throws IOException;

    /** Liste des fichiers d'un dossier (bornée, compte manquant). */
    Bounded listFiles(String relativePath, int maxEntries) throws IOException;

    /** Recherche de texte dans le projet (bornée, compte manquant). */
    Bounded searchText(String query, int maxHits) throws IOException;

    /** Écriture (mutation, permission requise) : retourne la taille écrite. */
    long writeFile(String relativePath, String content) throws IOException;

    /** Création de fichier (mutation, permission requise). */
    boolean createFile(String relativePath, String content) throws IOException;

    /** Suppression (mutation, permission requise) : irréversible si retour vrai. */
    boolean deleteFile(String relativePath) throws IOException;

    /** Lecture de l'aperçu avant/après pour le diff à l'approbation (REF-117). */
    String fileBefore(String relativePath) throws IOException;

    /**
     * Implémentation hôte sur le dossier d'un projet (chemins relatifs
     * seulement, bornage à l'arrivée).
     */
    final class OnProject implements AgentWorkspace {

        private final Path root;
        private final int maxFileChars;

        public OnProject(Path root) {
            this(root, 4_000);
        }

        public OnProject(Path root, int maxFileChars) {
            this.root = root;
            this.maxFileChars = maxFileChars;
        }

        @Override
        public Bounded readFile(String relativePath, int firstLine, int maxLines)
                throws IOException {
            Path file = resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                throw new IOException("Fichier introuvable : " + relativePath);
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, firstLine - 1);
            int limit = Math.min(maxLines, lines.size() - from);
            if (limit <= 0) {
                return new Bounded("(fichier de " + lines.size() + " lignes, fenêtre vide)",
                        lines.size() - from);
            }
            StringBuilder content = new StringBuilder();
            for (int i = from; i < from + limit; i++) {
                content.append(i + 1).append(": ").append(lines.get(i)).append('\n');
            }
            return new Bounded(content.toString(), lines.size() - from - limit);
        }

        @Override
        public Bounded listFiles(String relativePath, int maxEntries) throws IOException {
            Path dir = resolve(relativePath);
            if (!Files.isDirectory(dir)) {
                throw new IOException("Dossier introuvable : " + relativePath);
            }
            List<String> names = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> names.add(p.getFileName().toString()));
            }
            names.sort(String::compareTo);
            int limit = Math.min(maxEntries, names.size());
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                content.append(names.get(i)).append('\n');
            }
            if (names.size() > limit) {
                content.append("(").append(names.size() - limit)
                        .append(" entrées manquantes — affinez la requête)");
            }
            return new Bounded(content.toString(), names.size() - limit);
        }

        @Override
        public Bounded searchText(String query, int maxHits) throws IOException {
            List<String> hits = new ArrayList<>();
            try (var stream = Files.walk(root)) {
                for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    if (hits.size() >= maxHits + 1) {
                        break;
                    }
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains(query)) {
                            hits.add(root.relativize(file) + ":" + (i + 1) + ": "
                                    + truncate(lines.get(i)));
                            break; // une par fichier : vue d'ensemble bornée
                        }
                    }
                }
            }
            int limit = Math.min(maxHits, hits.size());
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                content.append(hits.get(i)).append('\n');
            }
            return new Bounded(content.toString(), hits.size() - limit);
        }

        @Override
        public long writeFile(String relativePath, String content) throws IOException {
            Path file = resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return content.length();
        }

        @Override
        public boolean createFile(String relativePath, String content) throws IOException {
            Path file = resolve(relativePath);
            if (Files.exists(file)) {
                return false;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return true;
        }

        @Override
        public boolean deleteFile(String relativePath) throws IOException {
            Path file = resolve(relativePath);
            return Files.deleteIfExists(file);
        }

        @Override
        public String fileBefore(String relativePath) throws IOException {
            Path file = resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }

        private String truncate(String line) {
            String trimmed = line.strip();
            return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120) + "…";
        }

        /** Chemin relatif résolu et confiné au projet (traversée refusée). */
        private Path resolve(String relativePath) throws IOException {
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root)) {
                throw new IOException("Chemin hors du projet refusé : " + relativePath);
            }
            return file;
        }
    }

    /** Fake en mémoire pour les tests (REF-114 : le port est testable sans disque). */
    final class InMemory implements AgentWorkspace {

        private final java.util.Map<String, String> files = new java.util.LinkedHashMap<>();

        public void put(String path, String content) {
            files.put(path, content);
        }

        public String get(String path) {
            return files.get(path);
        }

        public java.util.Set<String> paths() {
            return java.util.Collections.unmodifiableSet(files.keySet());
        }

        @Override
        public Bounded readFile(String relativePath, int firstLine, int maxLines) {
            String content = files.get(relativePath);
            if (content == null) {
                throw new java.io.UncheckedIOException(
                        new java.io.IOException("Fichier introuvable : " + relativePath));
            }
            return new Bounded(content, 0);
        }

        @Override
        public Bounded listFiles(String relativePath, int maxEntries) {
            List<String> names = files.keySet().stream()
                    .filter(p -> p.startsWith(relativePath))
                    .toList();
            int limit = Math.min(maxEntries, names.size());
            return new Bounded(String.join("\n", names.subList(0, limit)),
                    names.size() - limit);
        }

        @Override
        public Bounded searchText(String query, int maxHits) {
            List<String> hits = files.entrySet().stream()
                    .filter(e -> e.getValue().contains(query))
                    .map(e -> e.getKey() + ":1")
                    .toList();
            int limit = Math.min(maxHits, hits.size());
            return new Bounded(String.join("\n", hits.subList(0, limit)),
                    hits.size() - limit);
        }

        @Override
        public long writeFile(String relativePath, String content) {
            files.put(relativePath, content);
            return content.length();
        }

        @Override
        public boolean createFile(String relativePath, String content) {
            return files.putIfAbsent(relativePath, content) == null;
        }

        @Override
        public boolean deleteFile(String relativePath) {
            return files.remove(relativePath) != null;
        }

        @Override
        public String fileBefore(String relativePath) {
            return files.getOrDefault(relativePath, "");
        }
    }
}
