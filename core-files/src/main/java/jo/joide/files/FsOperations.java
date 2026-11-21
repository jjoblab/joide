package jo.joide.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Opérations de fichiers du tiroir gauche (Lot 3) : créer, renommer,
 * déplacer, copier, mettre à la **corbeille restaurable** — tout est
 * confiné à la racine du projet (traversée « .. » refusée, dossiers
 * protégés .git/.joide intouchables par les opérations publiques),
 * jamais d'écrasement silencieux (cible existante = erreur honnête) et
 * déplacements **atomiques** quand le système de fichiers l'accepte (avec
 * repli explicite sinon).
 *
 * <p>La corbeille vit sous {@code <projet>/.joide/corbeille/} (jamais
 * listée par {@link FileTree}) avec un index ligne à ligne
 * {@code chemin d'origine<TAB>millisecondes<TAB>taille<TAB>nom en
 * corbeille} — format simple et lisible, aucune dépendance de sérialisation
 * (politique de licences du module).</p>
 */
public final class FsOperations {

    /** Dossier de la corbeille, relatif à la racine du projet. */
    public static final String TRASH_RELATIVE = ".joide/corbeille";

    /** Index de la corbeille (une entrée par ligne, tabulations). */
    static final String TRASH_INDEX = TRASH_RELATIVE + "/index.txt";

    /** Plafond d'entrées pour une copie de dossier (garde-fou mémoire). */
    public static final int MAX_COPY_ENTRIES = 500;

    private FsOperations() {}

    // ---- Créations ----------------------------------------------------------

    /**
     * Crée un fichier (contenu facultatif) ; les parents manquants sont
     * créés. Refuse un chemin existant — jamais d'écrasement.
     */
    public static void createFile(Path root, String relative, String content)
            throws IOException {
        Path target = writable(root, relative);
        if (Files.exists(target)) {
            throw new IOException("Existe déjà : " + relative);
        }
        Files.createDirectories(target.getParent());
        Files.createFile(target);
        if (content != null && !content.isEmpty()) {
            Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Crée un dossier (parents compris) ; refuse un chemin existant. */
    public static void createDirectory(Path root, String relative)
            throws IOException {
        Path target = writable(root, relative);
        if (Files.exists(target)) {
            throw new IOException("Existe déjà : " + relative);
        }
        Files.createDirectories(target);
    }

    // ---- Renommer, déplacer, copier ----------------------------------------

    /**
     * Renomme/déplace {@code from} vers {@code to} (chemin relatif cible
     * complet) : mouvement atomique si possible, repli explicite sinon.
     * Refuse si la cible existe.
     */
    public static void move(Path root, String from, String to) throws IOException {
        Path source = writable(root, from);
        Path target = writable(root, to);
        if (Files.exists(target)) {
            throw new IOException("La cible existe déjà : " + to);
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /**
     * Copie {@code from} (fichier ou dossier borné à {@value #MAX_COPY_ENTRIES}
     * entrées) vers {@code to} ; refuse si la cible existe.
     */
    public static void copy(Path root, String from, String to) throws IOException {
        Path source = readable(root, from);
        Path target = writable(root, to);
        if (Files.exists(target)) {
            throw new IOException("La cible existe déjà : " + to);
        }
        if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            return;
        }
        final int[] seen = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (++seen[0] > MAX_COPY_ENTRIES) {
                    throw new IOException("Copie tronquée : plafond de "
                            + MAX_COPY_ENTRIES + " entrées atteint");
                }
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (++seen[0] > MAX_COPY_ENTRIES) {
                    throw new IOException("Copie tronquée : plafond de "
                            + MAX_COPY_ENTRIES + " entrées atteint");
                }
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---- Corbeille restaurable ---------------------------------------------

    /** Une entrée de corbeille (index ligne à ligne). */
    public record TrashEntry(String originalPath, long whenMillis, long size,
            String trashName) {}

    /**
     * Met un chemin à la corbeille (déplacement réel, restaurable) :
     * {@code .joide/corbeille/<millisecondes>_<nom>} + ligne d'index.
     * Retourne l'entrée créée (pour un « Annuler » immédiat côté UI).
     */
    public static TrashEntry toTrash(Path root, String relative) throws IOException {
        Path source = readable(root, relative);
        if (isProtected(relative)) {
            throw new IOException("Chemin protégé : " + relative);
        }
        String name = source.getFileName().toString();
        if (name.contains("\t") || name.contains("\n") || relative.contains("\t")) {
            throw new IOException("Nom non gérable par la corbeille : " + relative);
        }
        long when = System.currentTimeMillis();
        Path trashDir = root.resolve(TRASH_RELATIVE);
        Files.createDirectories(trashDir);
        String trashName = when + "_" + name;
        Path trashed = trashDir.resolve(trashName);
        int suffix = 1;
        while (Files.exists(trashed)) {
            trashName = when + "_" + (suffix++) + "_" + name;
            trashed = trashDir.resolve(trashName);
        }
        try {
            Files.move(source, trashed, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, trashed);
        }
        long size = sizeOf(trashed);
        List<String> lines = new ArrayList<>(indexLines(root));
        lines.add(relative + "\t" + when + "\t" + size + "\t" + trashName);
        writeIndex(root, lines);
        return new TrashEntry(relative, when, size, trashName);
    }

    /** Entrées de corbeille (index filtré des fichiers absents). */
    public static List<TrashEntry> listTrash(Path root) throws IOException {
        List<TrashEntry> entries = new ArrayList<>();
        for (String line : indexLines(root)) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 4) {
                continue; // ligne illisible : ignorée honnêtement
            }
            if (!Files.exists(root.resolve(TRASH_RELATIVE).resolve(parts[3]))) {
                continue; // fichier disparu (purge externe) : pas listé
            }
            entries.add(new TrashEntry(parts[0], Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), parts[3]));
        }
        return entries;
    }

    /** Restaure une entrée (refuse si la cible existe déjà). */
    public static void restore(Path root, TrashEntry entry) throws IOException {
        Path trashed = root.resolve(TRASH_RELATIVE).resolve(entry.trashName());
        if (!Files.exists(trashed)) {
            throw new IOException("Entrée de corbeille introuvable : "
                    + entry.trashName());
        }
        Path target = root.resolve(entry.originalPath());
        if (Files.exists(target)) {
            throw new IOException("La cible existe déjà : " + entry.originalPath());
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(trashed, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(trashed, target);
        }
        removeIndexLine(root, entry.trashName());
    }

    /** Supprime définitivement une entrée (fichier ou dossier). */
    public static void deleteForever(Path root, TrashEntry entry) throws IOException {
        Path trashed = root.resolve(TRASH_RELATIVE).resolve(entry.trashName());
        if (Files.exists(trashed)) {
            deleteTree(trashed);
        }
        removeIndexLine(root, entry.trashName());
    }

    /** Vide toute la corbeille (fichiers + index). */
    public static void emptyTrash(Path root) throws IOException {
        Path trashDir = root.resolve(TRASH_RELATIVE);
        if (!Files.isDirectory(trashDir)) {
            return;
        }
        try (var stream = Files.list(trashDir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (!child.getFileName().toString().equals("index.txt")) {
                    deleteTree(child);
                }
            }
        }
        writeIndex(root, List.of());
    }

    // ---- Aides et gardes ----------------------------------------------------

    /** Chemin lisible confiné (source d'une opération). */
    private static Path readable(Path root, String relative) throws IOException {
        Path path = FileTree.resolve(root, relative);
        if (!Files.exists(path)) {
            throw new IOException("Introuvable : " + relative);
        }
        if (isProtected(relative)) {
            throw new IOException("Chemin protégé : " + relative);
        }
        return path;
    }

    /** Chemin inscriptible confiné (cible d'une opération), protégé refusé. */
    private static Path writable(Path root, String relative) throws IOException {
        if (isProtected(relative)) {
            throw new IOException("Chemin protégé : " + relative);
        }
        return FileTree.resolve(root, relative);
    }

    /** .git et le domaine interne .joide ne se touchent jamais directement. */
    private static boolean isProtected(String relative) {
        return relative.equals(".git") || relative.startsWith(".git/")
                || relative.equals(".joide") || relative.startsWith(".joide/");
    }

    private static long sizeOf(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }
        final long[] total = {0};
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static void deleteTree(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e)
                    throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<String> indexLines(Path root) throws IOException {
        Path index = root.resolve(TRASH_INDEX);
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        return Files.readAllLines(index, StandardCharsets.UTF_8);
    }

    private static void writeIndex(Path root, List<String> lines) throws IOException {
        Path index = root.resolve(TRASH_INDEX);
        Files.createDirectories(index.getParent());
        Files.write(index, lines, StandardCharsets.UTF_8);
    }

    private static void removeIndexLine(Path root, String trashName)
            throws IOException {
        List<String> kept = new ArrayList<>();
        for (String line : indexLines(root)) {
            String[] parts = line.split("\t", -1);
            if (parts.length == 4 && parts[3].equals(trashName)) {
                continue;
            }
            kept.add(line);
        }
        writeIndex(root, kept);
    }
}
