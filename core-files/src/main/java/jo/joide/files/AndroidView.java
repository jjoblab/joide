package jo.joide.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Vue « Android » de l'arborescence (Lot 3 du tiroir) : regroupement par
 * module — Gradle Scripts, Manifests, puis par module java (sources
 * principales et de test) et res — **adossé aux fichiers réels** : un
 * nœud n'apparaît que si son contenu existe sur le disque. Pur JVM,
 * borné (modules parcourus), testé.
 *
 * <p>Le squelette ne descend que d'un niveau : les enfants des dossiers
 * réels (packages, res/layout…) relèvent du pipeline de listing normal —
 * la vue Android n'est qu'un autre **squelette de tête** au-dessus du
 * même moteur. Les libellés sont des clés neutres ({@code @scripts},
 * {@code @java-test}…) : l'UI les traduit en ressources — jamais de texte
 * d'interface codé en dur dans le cœur.</p>
 */
public final class AndroidView {

    /** Clés de libellé neutres (traduites en ressources par l'UI). */
    public static final String LABEL_SCRIPTS = "@scripts";
    public static final String LABEL_MANIFESTS = "@manifests";
    public static final String LABEL_JAVA = "@java";
    public static final String LABEL_JAVA_TEST = "@java-test";
    public static final String LABEL_RES = "@res";

    /**
     * Un nœud de la vue Android : groupe virtuel (Gradle Scripts,
     * Manifests) ou dossier/fichier réel.
     *
     * @param id           identifiant stable (chemin réel ou clé de groupe).
     * @param display      libellé neutre : clé de groupe, ou nom de fichier,
     *                     ou nom de module.
     * @param qualifier    précision d'affichage (nom de module pour « (app) »),
     *                     null sinon.
     * @param kind         type d'icône.
     * @param group        groupe virtuel (pas de chemin réel).
     * @param directory    dossier (ou groupe) ouvrable.
     * @param relativePath chemin relatif réel, null pour un groupe.
     * @param children     enfants immédiats du squelette (groupes et modules).
     */
    public record Node(String id, String display, String qualifier, FileKind kind,
            boolean group, boolean directory, String relativePath,
            List<Node> children) {}

    private AndroidView() {}

    /**
     * Construit le squelette : modules = dossiers (racine comprise, profondeur
     * 1) contenant un {@code build.gradle} ; borné à {@code maxModules}.
     */
    public static List<Node> build(Path root, int maxModules,
            FileTree.Cancelled cancelled) throws IOException {
        List<String> modules = modules(root, maxModules, cancelled);
        List<Node> out = new ArrayList<>();

        // Gradle Scripts : fichiers de build du projet puis des modules.
        List<Node> scripts = new ArrayList<>();
        script(scripts, root, "build.gradle", null);
        script(scripts, root, "settings.gradle", null);
        script(scripts, root, "gradle.properties", null);
        script(scripts, root, "gradle/libs.versions.toml", null);
        script(scripts, root, "gradlew", null);
        for (String module : modules) {
            if (!module.equals(".")) {
                script(scripts, root, module + "/build.gradle", moduleName(module));
            }
        }
        if (!scripts.isEmpty()) {
            out.add(new Node("group:scripts", LABEL_SCRIPTS, null,
                    FileKind.GRADLE, true, true, null, scripts));
        }

        // Manifestes : un par module, s'il existe.
        List<Node> manifests = new ArrayList<>();
        for (String module : modules) {
            String path = module.equals(".")
                    ? "src/main/AndroidManifest.xml"
                    : module + "/src/main/AndroidManifest.xml";
            if (Files.isRegularFile(root.resolve(path))) {
                manifests.add(new Node(path, "AndroidManifest.xml",
                        module.equals(".") ? null : moduleName(module),
                        FileKind.MANIFEST, false, false, path, List.of()));
            }
        }
        if (!manifests.isEmpty()) {
            out.add(new Node("group:manifests", LABEL_MANIFESTS, null,
                    FileKind.MANIFEST, true, true, null, manifests));
        }

        // Modules : java (main + test) et res, seulement s'ils existent.
        for (String module : modules) {
            List<Node> children = new ArrayList<>();
            folder(children, root, module, "src/main/java", LABEL_JAVA,
                    FileKind.SRC_ROOT);
            folder(children, root, module, "src/test/java", LABEL_JAVA_TEST,
                    FileKind.TEST_ROOT);
            folder(children, root, module, "src/androidTest/java", LABEL_JAVA_TEST,
                    FileKind.TEST_ROOT);
            folder(children, root, module, "src/main/res", LABEL_RES,
                    FileKind.XML);
            if (!children.isEmpty()) {
                String display = module.equals(".")
                        ? (root.getFileName() != null
                                ? root.getFileName().toString() : "projet")
                        : moduleName(module);
                out.add(new Node("module:" + module, display, null,
                        FileKind.FOLDER, false, true,
                        module.equals(".") ? null : module, children));
            }
        }
        return out;
    }

    private static void script(List<Node> scripts, Path root, String relative,
            String qualifier) {
        if (Files.isRegularFile(root.resolve(relative))) {
            scripts.add(new Node(relative, new java.io.File(relative).getName(),
                    qualifier, FileKind.GRADLE, false, false, relative, List.of()));
        }
    }

    private static void folder(List<Node> children, Path root, String module,
            String relative, String label, FileKind kind) {
        String path = module.equals(".") ? relative : module + "/" + relative;
        if (Files.isDirectory(root.resolve(path))) {
            children.add(new Node(path, label, null, kind, false, true,
                    path, List.of()));
        }
    }

    /** Modules : la racine si elle construit, puis ses enfants qui construisent. */
    private static List<String> modules(Path root, int maxModules,
            FileTree.Cancelled cancelled) throws IOException {
        List<String> found = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("settings.gradle"))) {
            found.add(".");
        }
        try (var stream = Files.list(root)) {
            List<String> children = new ArrayList<>();
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (cancelled != null && cancelled.isCancelled()) {
                    break;
                }
                String name = child.getFileName().toString();
                if (!Files.isDirectory(child) || FileTree.isIgnoredDir(name)) {
                    continue;
                }
                if (Files.isRegularFile(child.resolve("build.gradle"))) {
                    children.add(name);
                    if (found.size() + children.size() >= maxModules) {
                        break; // garde-fou : le plafond se voit (modules manquants).
                    }
                }
            }
            children.sort(Comparator.naturalOrder());
            found.addAll(children);
        }
        return found;
    }

    /** Dernier segment d'un chemin relatif (« app/src » → « src »). */
    private static String moduleName(String module) {
        int slash = module.lastIndexOf('/');
        return slash < 0 ? module : module.substring(slash + 1);
    }
}
