package jo.joide.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Arborescence de fichiers du projet (Lot 1 du tiroir gauche) : listing
 * **borné** (profondeur et entrées), **annulable**, dossiers générés et
 * secrets ignorés, tri dossiers d'abord puis insensible à la casse. Pur JVM,
 * Java 8 strict — tourne sur l'appareil (API 28) sans désucrage.
 *
 * <p>Le listing ne charge JAMAIS tout l'arbre en mémoire : chaque niveau se
 * liste à la demande (l'UI ouvre l'arbre paresseusement) ; le garde-fou
 * d'entrées borne chaque lecture et signale la troncature.</p>
 */
public final class FileTree {

    /** Plafond d'entrées par listing (garde-fou des très gros dossiers). */
    public static final int DEFAULT_MAX_ENTRIES = 500;

    /** Dossiers jamais montrés ni parcourus (générés, VCS, secrets). */
    static boolean isIgnoredDir(String name) {
        return name.equals(".git") || name.equals(".gradle")
                || name.equals("build") || name.equals(".idea")
                || name.equals(".joide") || name.equals("node_modules")
                || name.equals(".cxx");
    }

    /** Un nœud de l'arborescence (dossier ou fichier, relatif à la racine). */
    public record Entry(String name, String relativePath, boolean directory,
            boolean truncated) {

        /** Catégorie d'icône (FileIcons) — l'UI n'analyse pas les noms. */
        public String iconCategory() {
            return FileIcons.categoryOf(name, directory);
        }
    }

    /** Résultat d'un listing : entrées triées + marque de troncature. */
    public record Listing(List<Entry> entries, boolean truncated) {}

    /** Vérification d'annulation : vrai = arrêter immédiatement. */
    public interface Cancelled {
        boolean isCancelled();
    }

    /**
     * Liste un dossier : dossiers d'abord (alphabétique insensible à la
     * casse), fichiers ensuite ; dossiers ignorés exclus ; fichiers cachés
     * filtrables ; borné à {@code maxEntries} (troncature signalée).
     *
     * @param root      racine du projet.
     * @param relative  dossier relatif à lister (« . » ou vide = racine).
     * @param showHidden vrai pour inclure les fichiers cachés (dotfiles).
     */
    public static Listing list(Path root, String relative, boolean showHidden,
            int maxEntries, Cancelled cancelled) throws IOException {
        Path dir = resolve(root, relative);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Dossier introuvable : " + relative);
        }
        List<Entry> dirs = new ArrayList<>();
        List<Entry> files = new ArrayList<>();
        int seen = 0;
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (cancelled != null && cancelled.isCancelled()) {
                    return new Listing(merge(dirs, files, maxEntries), true);
                }
                if (++seen > maxEntries * 2) {
                    break; // garde-fou mémoire : au-delà, on tronque honnêtement
                }
                String name = child.getFileName().toString();
                boolean isDir = Files.isDirectory(child);
                if (isDir && isIgnoredDir(name)) {
                    continue;
                }
                if (!showHidden && !isDir && name.startsWith(".")) {
                    continue;
                }
                String childRelative = ".".equals(relative) || relative.isEmpty()
                        ? name : relative + "/" + name;
                Entry entry = new Entry(name, childRelative, isDir, false);
                if (isDir) {
                    dirs.add(entry);
                } else {
                    files.add(entry);
                }
            }
        }
        Comparator<Entry> order = Comparator
                .comparing((Entry e) -> e.name().toLowerCase(Locale.ROOT));
        dirs.sort(order);
        files.sort(order);
        List<Entry> all = new ArrayList<>(dirs.size() + files.size());
        all.addAll(dirs);
        all.addAll(files);
        boolean truncated = all.size() > maxEntries;
        if (truncated) {
            all = new ArrayList<>(all.subList(0, maxEntries));
        }
        return new Listing(all, truncated);
    }

    private static List<Entry> merge(List<Entry> dirs, List<Entry> files,
            int maxEntries) {
        List<Entry> all = new ArrayList<>(dirs);
        all.addAll(files);
        return all.size() > maxEntries
                ? new ArrayList<>(all.subList(0, maxEntries)) : all;
    }

    /** Résout un chemin relatif confiné à la racine (traversée refusée). */
    static Path resolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || ".".equals(relative)) {
            return root;
        }
        if (relative.startsWith("/") || relative.contains("..")) {
            throw new IOException("Chemin refusé : " + relative);
        }
        Path dir = root.resolve(relative).normalize();
        if (!dir.startsWith(root)) {
            throw new IOException("Chemin hors du projet refusé : " + relative);
        }
        return dir;
    }

    // ---- Lot 3 : compte borné et chaîne compacte ---------------------------

    /** Nombre d'entrées d'un dossier (plafond atteint = dit honnêtement). */
    public record Count(int entries, boolean capped) {}

    /**
     * Compte les entrées (fichiers et dossiers, récursif) sous un dossier —
     * parcours en largeur **borné** à {@code maxEntries} et annulable ;
     * dossiers ignorés exclus. Le plafond atteint se dit
     * ({@link Count#capped}), jamais de compte menteur.
     */
    public static Count countAll(Path root, String relative, int maxEntries,
            Cancelled cancelled) throws IOException {
        Path start = resolve(root, relative);
        if (!Files.isDirectory(start)) {
            throw new IOException("Dossier introuvable : " + relative);
        }
        List<Path> queue = new ArrayList<>();
        queue.add(start);
        int count = 0;
        while (!queue.isEmpty()) {
            if (cancelled != null && cancelled.isCancelled()) {
                return new Count(count, true);
            }
            Path dir = queue.remove(0);
            try (var stream = Files.list(dir)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    String name = child.getFileName().toString();
                    if (Files.isDirectory(child)) {
                        if (isIgnoredDir(name)) {
                            continue;
                        }
                        count++;
                        if (count >= maxEntries) {
                            return new Count(count, true);
                        }
                        queue.add(child);
                    } else {
                        count++;
                        if (count >= maxEntries) {
                            return new Count(count, true);
                        }
                    }
                }
            }
        }
        return new Count(count, false);
    }

    /** Une chaîne de dossiers compacts (« com.example.app » sur une ligne). */
    public record Chain(String display, String lastRelative, int levels) {}

    /**
     * Chaîne compacte depuis un dossier : descend tant que le dossier ne
     * contient **qu'un seul enfant, un dossier** (aucun fichier à côté) —
     * convention des arborescences d'IDE pour les packages. borné à
     * {@code maxDepth} niveaux, annulable ; sans chaîne, retourne le dossier
     * tel quel ({@code levels = 1}).
     */
    public static Chain compactChain(Path root, String relative, int maxDepth,
            Cancelled cancelled) throws IOException {
        String display = fileNameOf(relative);
        String current = normalizeRelative(relative);
        int levels = 1;
        while (levels < maxDepth) {
            if (cancelled != null && cancelled.isCancelled()) {
                break;
            }
            Listing listing = list(root, current, true, 2, null);
            if (listing.entries().size() != 1 || !listing.entries().get(0).directory()) {
                break; // plusieurs enfants, ou un fichier : la chaîne s'arrête.
            }
            Entry only = listing.entries().get(0);
            current = only.relativePath();
            display += "." + only.name();
            levels++;
        }
        return new Chain(display, current, levels);
    }

    /** Nom de fichier d'un chemin relatif (« . » = chaîne vide). */
    private static String fileNameOf(String relative) {
        String normalized = normalizeRelative(relative);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    /** « . » et vide normalisés ; « ./x » → « x » ; pas de « / » final. */
    private static String normalizeRelative(String relative) {
        if (relative == null || relative.isEmpty() || ".".equals(relative)) {
            return "";
        }
        String p = relative;
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
