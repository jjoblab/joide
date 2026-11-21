package jo.joide.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Port espace de travail étroit de l'agent (conception §8, REF-114) : **aucun
 * outil ne touche le projet directement** — tout passe par cette interface,
 * implémentée par l'hôte sur le dossier du projet (et par un fake en mémoire
 * dans les tests). Les lectures bornent leurs résultats **à l'arrivée** (avec
 * compte de ce qui manque — REF-113).
 *
 * <p>Lot 3 — durcissement : l'évasion de chemin ({@code ..}, liens
 * symboliques sortant du projet, chemins absolus) est refusée ; les
 * **chemins protégés en écriture** ({@code .git/}, {@code local.properties},
 * {@code .env}, magasins de clés) sont rejetés ; les fichiers binaires et
 * surdimensionnés ne sont ni lus ni écrits ; l'arborescence ignore les
 * dossiers générés ; la recherche devient une vraie recherche (regex, casse,
 * mot entier, glob, contexte) ; {@code applyEdit} fait le remplacement ciblé
 * (échoue sur 0 ou n occurrences) et {@code renameFile} le renommage sûr.
 * Plus aucune API Java 11+ (API 28 sans désucrage — ADR-009).</p>
 */
public interface AgentWorkspace {

    /** Taille maximale d'un fichier lu ou écrit (garde-fou). */
    int MAX_FILE_BYTES = 512 * 1024;

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

    /**
     * Arborescence bornée (dossiers et fichiers, dossiers générés ignorés,
     * profondeur limitée — Lot 3).
     */
    Bounded listTree(String relativePath, int maxDepth, int maxEntries) throws IOException;

    /** Lecture complète (bornée en taille, binaire refusé). */
    String readText(String relativePath) throws IOException;

    /**
     * Recherche de texte (Lot 3) : vraie requête, options (regex, casse, mot
     * entier, glob, lignes de contexte), résultats « chemin:ligne: extrait ».
     */
    Bounded search(String query, SearchOptions options, int maxHits) throws IOException;

    /** Écriture (mutation, permission requise) : retourne la taille écrite. */
    long writeFile(String relativePath, String content) throws IOException;

    /** Création de fichier (mutation, permission requise). */
    boolean createFile(String relativePath, String content) throws IOException;

    /** Suppression (mutation, permission requise) : irréversible si retour vrai. */
    boolean deleteFile(String relativePath) throws IOException;

    /** Renommage/déplacement (mutation, permission requise, cible protégée refusée). */
    boolean renameFile(String from, String to) throws IOException;

    /**
     * Remplacement ciblé (mutation, permission requise — l'outil PAR DÉFAUT du
     * Lot 3) : remplace {@code oldText} par {@code newText}. Échoue sur 0
     * occurrence (texte introuvable — relis le fichier) comme sur plusieurs
     * (ambigu — précise le contexte) ; retourne la taille écrite.
     */
    long applyEdit(String relativePath, String oldText, String newText) throws IOException;

    /** Lecture de l'aperçu avant/après pour le diff à l'approbation (REF-117). */
    String fileBefore(String relativePath) throws IOException;

    /**
     * Implémentation hôte sur le dossier d'un projet (chemins relatifs
     * seulement, bornage et garde-fous à l'arrivée).
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
            List<String> lines = Files.readAllLines(readable(relativePath),
                    StandardCharsets.UTF_8);
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
        public Bounded listTree(String relativePath, int maxDepth, int maxEntries)
                throws IOException {
            Path base = resolve(relativePath);
            if (!Files.isDirectory(base)) {
                throw new IOException("Dossier introuvable : " + relativePath);
            }
            List<String> entries = new ArrayList<>();
            walkTree(base, root.relativize(base).toString(), 0, maxDepth, maxEntries,
                    entries, new int[] {0});
            StringBuilder content = new StringBuilder();
            int limit = Math.min(entries.size(), maxEntries);
            for (int i = 0; i < limit; i++) {
                content.append(entries.get(i)).append('\n');
            }
            return new Bounded(content.toString(), Math.max(0, entries.size() - limit));
        }

        /** Parcours en profondeur borné : dossiers avec « / », fichiers tels quels. */
        private void walkTree(Path dir, String shown, int depth, int maxDepth,
                int maxEntries, List<String> entries, int[] visited) throws IOException {
            if (depth >= maxDepth || entries.size() >= maxEntries) {
                return;
            }
            List<Path> children = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.forEach(children::add);
            }
            children.sort(Comparator
                    .comparing((Path p) -> !Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
            for (Path child : children) {
                if (entries.size() >= maxEntries) {
                    return;
                }
                String name = child.getFileName().toString();
                if (isGeneratedDir(name) || name.equals(".git")) {
                    continue; // jamais .git/, ni les dossiers générés
                }
                visited[0]++;
                String childShown = shown.isEmpty() ? name : shown + "/" + name;
                if (Files.isDirectory(child)) {
                    entries.add(childShown + "/");
                    walkTree(child, childShown, depth + 1, maxDepth, maxEntries,
                            entries, visited);
                } else {
                    entries.add(childShown);
                }
            }
        }

        @Override
        public String readText(String relativePath) throws IOException {
            Path file = readable(relativePath);
            String content = TurnCheckpoint.readUtf8(file);
            if (content.length() > maxFileChars) {
                return content.substring(0, maxFileChars)
                        + "\n… (tronqué : " + (content.length() - maxFileChars)
                        + " caractères restants — utilise la fenêtre de lignes)";
            }
            return content;
        }

        @Override
        public Bounded search(String query, SearchOptions options, int maxHits)
                throws IOException {
            if (query == null || query.isEmpty()) {
                throw new IOException("Requête vide — précise le texte cherché.");
            }
            Pattern pattern = compilePattern(query, options);
            List<String> hits = new ArrayList<>();
            int[] scanned = {0};
            try (var stream = Files.walk(root)) {
                for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                    if (hits.size() >= maxHits) {
                        break;
                    }
                    String name = file.getFileName().toString();
                    if (name.startsWith(".") && !name.equals(".gitignore")) {
                        continue;
                    }
                    if (isUnderGeneratedDir(file)) {
                        continue;
                    }
                    if (!options.matchesFilter(name)) {
                        continue;
                    }
                    scanned[0]++;
                    searchInFile(file, pattern, options, maxHits, hits);
                }
            }
            StringBuilder content = new StringBuilder();
            for (String hit : hits) {
                content.append(hit).append('\n');
            }
            content.append("(").append(scanned[0]).append(" fichiers parcourus)");
            return new Bounded(content.toString(), Math.max(0, 0));
        }

        /** Cherche les occurrences d'un fichier (une ligne par occurrence, borné). */
        private void searchInFile(Path file, Pattern pattern, SearchOptions options,
                int maxHits, List<String> hits) throws IOException {
            if (!isReadableText(file)) {
                return; // binaire : ignoré, jamais affiché comme résultat
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String relative = root.relativize(file).toString();
            for (int i = 0; i < lines.size() && hits.size() < maxHits; i++) {
                Matcher matcher = pattern.matcher(lines.get(i));
                int from = 0;
                while (matcher.find(from) && hits.size() < maxHits) {
                    if (options.wholeWord()
                            && !SearchOptions.isWholeWord(lines.get(i), matcher.start(),
                                    matcher.end())) {
                        from = matcher.end();
                        continue;
                    }
                    hits.add(relative + ":" + (i + 1) + ": " + excerpt(lines.get(i)));
                    for (int c = 1; c <= options.contextLines() && i + c < lines.size(); c++) {
                        hits.add("    " + (i + c + 1) + ": " + excerpt(lines.get(i + c)));
                    }
                    break; // une occurrence par ligne (vue d'ensemble bornée)
                }
            }
        }

        /** Motif selon les options (texte littéral échappé, ou regex validée). */
        private static Pattern compilePattern(String query, SearchOptions options)
                throws IOException {
            int flags = options.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE;
            try {
                return Pattern.compile(options.regex() ? query
                        : Pattern.quote(query), flags);
            } catch (PatternSyntaxException e) {
                throw new IOException("Expression régulière invalide : "
                        + e.getMessage());
            }
        }

        @Override
        public long writeFile(String relativePath, String content) throws IOException {
            Path file = writable(relativePath);
            TurnCheckpoint.writeUtf8(file, content);
            return content.length();
        }

        @Override
        public boolean createFile(String relativePath, String content) throws IOException {
            Path file = writable(relativePath);
            if (Files.exists(file)) {
                return false;
            }
            TurnCheckpoint.writeUtf8(file, content);
            return true;
        }

        @Override
        public boolean deleteFile(String relativePath) throws IOException {
            Path file = writable(relativePath);
            return Files.deleteIfExists(file);
        }

        @Override
        public boolean renameFile(String from, String to) throws IOException {
            Path source = writable(from);
            Path target = writable(to);
            if (!Files.exists(source) || Files.exists(target)) {
                return false;
            }
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            return true;
        }

        @Override
        public long applyEdit(String relativePath, String oldText, String newText)
                throws IOException {
            if (oldText == null || oldText.isEmpty()) {
                throw new IOException("ancien_texte vide : précise le texte exact à remplacer.");
            }
            Path file = writable(relativePath);
            if (!Files.isRegularFile(file)) {
                throw new IOException("Fichier introuvable : " + relativePath);
            }
            String content = TurnCheckpoint.readUtf8(file);
            int first = content.indexOf(oldText);
            if (first < 0) {
                throw new IOException("Texte introuvable dans " + relativePath
                        + " : relis le fichier (fenêtre de lignes) et réessaie "
                        + "avec le texte exact, espaces compris.");
            }
            if (content.indexOf(oldText, first + 1) >= 0) {
                throw new IOException("Texte ambigu : plusieurs occurrences dans "
                        + relativePath + " — ajoute du contexte autour pour "
                        + "cibler la bonne, ou utilise une fenêtre plus longue.");
            }
            String updated = content.substring(0, first) + newText
                    + content.substring(first + oldText.length());
            TurnCheckpoint.writeUtf8(file, updated);
            return updated.length();
        }

        @Override
        public String fileBefore(String relativePath) throws IOException {
            Path file = resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return TurnCheckpoint.readUtf8(file);
        }

        // ---- Garde-fous -------------------------------------------------------

        /** Fichier lisible : existe, régulier, non binaire, taille bornée. */
        private Path readable(String relativePath) throws IOException {
            Path file = resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                throw new IOException("Fichier introuvable : " + relativePath);
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new IOException("Fichier trop volumineux ("
                        + Files.size(file) / 1024 + " Ko, plafond "
                        + MAX_FILE_BYTES / 1024 + " Ko) : utilise la fenêtre de lignes.");
            }
            if (!isReadableText(file)) {
                throw new IOException("Fichier binaire refusé : " + relativePath);
            }
            return file;
        }

        /** Cible d'écriture : chemin confiné, non protégé, taille bornée. */
        private Path writable(String relativePath) throws IOException {
            Path file = resolve(relativePath);
            if (isProtectedWrite(relativePath)) {
                throw new IOException("Chemin protégé en écriture : " + relativePath
                        + " (dépôt Git, secrets ou signature) — refusé.");
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            return file;
        }

        /** Chemin relatif résolu et confiné au projet (traversée et liens refusés). */
        private Path resolve(String relativePath) throws IOException {
            if (relativePath == null || relativePath.trim().isEmpty()) {
                throw new IOException("Chemin vide — précise un chemin relatif au projet.");
            }
            if (relativePath.startsWith("/") || relativePath.contains(":\\")
                    || Paths.get(relativePath).isAbsolute()) {
                throw new IOException("Chemin absolu refusé : " + relativePath
                        + " — les chemins sont relatifs à la racine du projet.");
            }
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root)) {
                throw new IOException("Chemin hors du projet refusé : " + relativePath);
            }
            // Lien symbolique sortant du projet : résolu réellement, puis confiné.
            if (Files.exists(file)) {
                try {
                    Path real = file.toRealPath();
                    if (!real.startsWith(root.toRealPath())) {
                        throw new IOException("Lien symbolique sortant du projet refusé : "
                                + relativePath);
                    }
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("refusé")) {
                        throw e;
                    }
                    // toRealPath échoue sur un fichier absent : créations admises.
                }
            }
            return file;
        }

        /** Chemins jamais modifiables par l'agent. */
        static boolean isProtectedWrite(String relativePath) {
            String path = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (path.equals(".gitignore")) {
                return false; // éditable : c'est du texte de configuration normal
            }
            return path.startsWith(".git/") || path.equals(".git")
                    || path.equals("local.properties") || path.endsWith("/local.properties")
                    || path.equals(".env") || path.endsWith("/.env")
                    || path.endsWith(".jks") || path.endsWith(".keystore")
                    || path.endsWith(".p12") || path.endsWith(".pem");
        }

        /** Dossiers générés : jamais listés dans l'arborescence. */
        static boolean isGeneratedDir(String name) {
            return name.equals("build") || name.equals(".gradle")
                    || name.equals(".idea") || name.equals("node_modules")
                    || name.equals(".cxx") || name.equals(".joide");
        }

        private boolean isUnderGeneratedDir(Path file) {
            for (Path part : root.relativize(file)) {
                if (isGeneratedDir(part.toString()) || part.toString().equals(".git")) {
                    return true;
                }
            }
            return false;
        }

        /** Détection de binaire : octet nul dans les 8 premiers Ko. */
        private static boolean isReadableText(Path file) throws IOException {
            byte[] head = new byte[8_192];
            try (var in = Files.newInputStream(file)) {
                int read = in.read(head);
                for (int i = 0; i < read; i++) {
                    if (head[i] == 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static String excerpt(String line) {
            String trimmed = line.trim();
            return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120) + "…";
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
            List<String> names = new ArrayList<>();
            for (String path : files.keySet()) {
                if (relativePath == null || relativePath.isEmpty()
                        || ".".equals(relativePath) || path.startsWith(relativePath + "/")) {
                    names.add(path);
                }
            }
            int limit = Math.min(maxEntries, names.size());
            return new Bounded(String.join("\n",
                    names.subList(0, limit)) + "\n", names.size() - limit);
        }

        @Override
        public Bounded listTree(String relativePath, int maxDepth, int maxEntries) {
            return listFiles(relativePath, maxEntries);
        }

        @Override
        public String readText(String relativePath) {
            String content = files.get(relativePath);
            if (content == null) {
                throw new java.io.UncheckedIOException(
                        new java.io.IOException("Fichier introuvable : " + relativePath));
            }
            return content;
        }

        @Override
        public Bounded search(String query, SearchOptions options, int maxHits) {
            List<String> hits = new ArrayList<>();
            Pattern pattern = Pattern.compile(options.regex() ? query
                    : Pattern.quote(query),
                    options.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE);
            for (java.util.Map.Entry<String, String> entry : files.entrySet()) {
                String fileName = entry.getKey().substring(
                        entry.getKey().lastIndexOf('/') + 1);
                if (!options.matchesFilter(fileName)) {
                    continue;
                }
                String[] lines = entry.getValue().split("\n", -1);
                for (int i = 0; i < lines.length && hits.size() < maxHits; i++) {
                    Matcher matcher = pattern.matcher(lines[i]);
                    if (matcher.find() && (!options.wholeWord()
                            || SearchOptions.isWholeWord(lines[i], matcher.start(),
                                    matcher.end()))) {
                        hits.add(entry.getKey() + ":" + (i + 1) + ": "
                                + lines[i].trim());
                        break;
                    }
                }
            }
            return new Bounded(String.join("\n", hits) + "\n", 0);
        }

        @Override
        public long writeFile(String relativePath, String content) {
            files.put(relativePath, content);
            return content.length();
        }

        @Override
        public boolean createFile(String relativePath, String content) {
            if (files.containsKey(relativePath)) {
                return false;
            }
            files.put(relativePath, content);
            return true;
        }

        @Override
        public boolean deleteFile(String relativePath) {
            return files.remove(relativePath) != null;
        }

        @Override
        public boolean renameFile(String from, String to) {
            if (!files.containsKey(from) || files.containsKey(to)) {
                return false;
            }
            files.put(to, files.remove(from));
            return true;
        }

        @Override
        public long applyEdit(String relativePath, String oldText, String newText)
                throws IOException {
            String content = files.get(relativePath);
            if (content == null) {
                throw new IOException("Fichier introuvable : " + relativePath);
            }
            int first = content.indexOf(oldText);
            if (first < 0) {
                throw new IOException("Texte introuvable dans " + relativePath);
            }
            if (content.indexOf(oldText, first + 1) >= 0) {
                throw new IOException("Texte ambigu : plusieurs occurrences");
            }
            String updated = content.substring(0, first) + newText
                    + content.substring(first + oldText.length());
            files.put(relativePath, updated);
            return updated.length();
        }

        @Override
        public String fileBefore(String relativePath) {
            String content = files.get(relativePath);
            return content == null ? "" : content;
        }
    }
}
