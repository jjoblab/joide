package jo.joide.coremodel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Codec {@code forge.json} (lot L10) : lecture, mutation ciblée et écriture atomique du
 * modèle de projet Forge — **le modèle réel du moteur**, jamais une copie parallèle.
 *
 * <p>Stratégie fidèle : le fichier est analysé en un arbre de valeurs ordonnées
 * ({@code Map/List/String/Long/Boolean/null}) ; les vues exposées à l'UI sont des
 * enregistrements en lecture seule ; les mutations s'appliquent **sur l'arbre** (champ
 * par champ) puis l'arbre entier est réécrit — ainsi les champs connus de Forge mais
 * inconnus de JoIDE (une future facette, un bloc inédit) traversent l'édition sans
 * perte, et l'ordre des clés existantes est préservé.</p>
 *
 * <p>Conventions de lecture alignées sur le codec du moteur (constat
 * {@code ModelCodec}) : {@code sources} absentes → {@code src/main/java} (Java) ou
 * {@code src/main/kotlin} (Kotlin) ; {@code resources} absentes →
 * {@code src/main/resources} ; {@code javaRelease} absent → 17 ; {@code language}
 * absente → java ; types {@code java-library}, {@code application},
 * {@code android-library} ; portées {@code api}, {@code implementation},
 * {@code compileOnly}, {@code runtimeOnly}.</p>
 *
 * <p>Parseur maison dans la règle du dépôt (aucune bibliothèque JSON par module) ;
 * écriture atomique (temporaire + renommage) : un crash pendant la sauvegarde ne
 * laisse jamais un {@code forge.json} à moitié écrit.</p>
 */
public final class ForgeJson {

    private ForgeJson() {}

    // ---- Vues de lecture (l'UI n'accède jamais à l'arbre) --------------------

    /**
     * Facette Android d'un module ({@code application} / {@code android-library}).
     */
    public record AndroidFacet(String manifest, List<String> res, int minSdk,
            int targetSdk, String namespace, int versionCode, String versionName,
            boolean buildConfig, boolean viewBinding) {}

    /**
     * Dépendance d'un module : {@code kind} vaut {@code project} ou {@code maven}.
     * Pour un projet : {@code module} renseigné. Pour Maven : groupe/artefact/version.
     */
    public record Dependency(String kind, String module, String group, String artifact,
            String version, String scope) {

        /** Coordonnée affichée ({@code g:a:v} ou {@code :module}). */
        public String display() {
            return "project".equals(kind) ? ":" + module
                    : group + ":" + artifact + ":" + version;
        }
    }

    /** Vue de lecture d'un module (conventions appliquées, valeurs absentes par défaut). */
    public record ModuleView(String name, String type, String language, int javaRelease,
            List<String> sources, List<String> resources, List<Dependency> dependencies,
            Optional<AndroidFacet> android) {}

    /** Erreur du codec (fichier illisible, structure inattendue). */
    public static final class ForgeJsonException extends Exception {
        ForgeJsonException(String message) {
            super(message);
        }
    }

    // ---- Modèle éditable ------------------------------------------------------

    /**
     * Le modèle d'un {@code forge.json} : arbre ordonné + mutations ciblées + écriture
     * atomique. Les mutations ne valident pas la cohérence buildable (c'est le rôle du
     * moteur au prochain build — l'écran reste honnête sur l'échec éventuel).
     */
    public static final class Model {

        private final Path file;
        private final Map<String, Object> root;

        private Model(Path file, Map<String, Object> root) {
            this.file = file;
            this.root = root;
        }

        /**
         * Lit un {@code forge.json}.
         *
         * @throws IOException en cas d'échec de lecture disque.
         * @throws ForgeJsonException si la structure n'est pas un modèle de projet.
         */
        public static Model read(Path file) throws IOException, ForgeJsonException {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Object parsed;
            try {
                parsed = JsonParser.parse(text);
            } catch (JsonSyntax e) {
                throw new ForgeJsonException("forge.json illisible : " + e.getMessage());
            }
            if (!(parsed instanceof Map)) {
                throw new ForgeJsonException("forge.json : la racine n'est pas un objet");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            return new Model(file, root);
        }

        /** Chemin du fichier porté par le modèle. */
        public Path file() {
            return file;
        }

        /** Dépôts Maven déclarés (chaînes d'URL, ordre du fichier). */
        public List<String> repositories() {
            return stringList(root.get("repositories"));
        }

        /** Ajoute un dépôt (refusé s'il existe déjà — retour : ajouté ?). */
        public boolean addRepository(String url) {
            List<Object> list = mutableList("repositories");
            for (Object existing : list) {
                if (url.equals(existing)) {
                    return false;
                }
            }
            list.add(url);
            return true;
        }

        /** Retire un dépôt (retour : retiré ?). */
        public boolean removeRepository(String url) {
            return mutableList("repositories").remove(url);
        }

        /** Modules du projet (vues avec conventions), ordre du fichier. */
        public List<ModuleView> modules() {
            List<ModuleView> views = new ArrayList<>();
            for (Object raw : listOrEmpty(root.get("modules"))) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> module = (Map<String, Object>) raw;
                views.add(moduleView(module));
            }
            return views;
        }

        /** Vue d'un module nommé (null si absent). */
        public ModuleView module(String name) {
            Map<String, Object> module = moduleMap(name);
            return module == null ? null : moduleView(module);
        }

        /** Ajoute un module (retour : ajouté ? — un doublon de nom est refusé). */
        public boolean addModule(String name, String type, int javaRelease) {
            if (moduleMap(name) != null) {
                return false;
            }
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("name", name);
            module.put("type", type);
            if (javaRelease != 17) {
                module.put("javaRelease", (long) javaRelease);
            }
            mutableList("modules").add(module);
            return true;
        }

        /**
         * Retire un module du modèle. Le dossier du module n'est PAS touché ici
         * (l'écran le fait séparément, avec confirmation — comme la référence).
         */
        public boolean removeModule(String name) {
            return mutableList("modules").removeIf(raw -> raw instanceof Map
                    && name.equals(((Map<?, ?>) raw).get("name")));
        }

        /** Renomme un module (dépendances des frères comprises). */
        public boolean renameModule(String from, String to) {
            Map<String, Object> module = moduleMap(from);
            if (module == null || moduleMap(to) != null) {
                return false;
            }
            module.put("name", to);
            for (Object raw : listOrEmpty(root.get("modules"))) {
                if (raw instanceof Map) {
                    for (Object dep : listOrEmpty(((Map<?, ?>) raw).get("dependencies"))) {
                        if (dep instanceof Map && from.equals(((Map<?, ?>) dep).get("project"))) {
                            ((Map<Object, Object>) dep).put("project", to);
                        }
                    }
                }
            }
            return true;
        }

        /** Ajoute un répertoire source à un module (retour : ajouté ?). */
        public boolean addSourceRoot(String moduleName, String dir) {
            Map<String, Object> module = requireModule(moduleName);
            List<String> current = effectiveSources(module);
            if (current.contains(dir)) {
                return false;
            }
            if (current.equals(defaultSources(module))) {
                // matérialiser la convention avant d'ajouter la première racine exotique
                List<Object> sources = new ArrayList<>(current);
                sources.add(dir);
                module.put("sources", sources);
                return true;
            }
            @SuppressWarnings("unchecked")
            List<Object> sources = (List<Object>) module.computeIfAbsent("sources",
                    k -> new ArrayList<Object>());
            sources.add(dir);
            return true;
        }

        /** Retire un répertoire source (retour : retiré ?). */
        public boolean removeSourceRoot(String moduleName, String dir) {
            Map<String, Object> module = requireModule(moduleName);
            List<String> current = effectiveSources(module);
            if (!current.contains(dir)) {
                return false;
            }
            List<Object> kept = new ArrayList<>(current);
            kept.remove(dir);
            module.put("sources", kept);
            return true;
        }

        /** Écrit un champ simple du module ({@code type}, {@code language}…). */
        public void setModuleField(String moduleName, String field, Object value) {
            requireModule(moduleName).put(field, value);
        }

        /** Écrit un champ entier de la facette Android (la crée s'il faut). */
        public void setAndroidField(String moduleName, String field, long value) {
            androidOf(requireModule(moduleName)).put(field, value);
        }

        /** Écrit un champ texte de la facette Android (la crée s'il faut). */
        public void setAndroidField(String moduleName, String field, String value) {
            androidOf(requireModule(moduleName)).put(field, value);
        }

        /** Ajoute une dépendance à un module (retour : ajoutée ? — doublon refusé). */
        public boolean addDependency(String moduleName, Dependency dependency) {
            Map<String, Object> module = requireModule(moduleName);
            for (Dependency existing : moduleView(module).dependencies()) {
                if (existing.kind.equals(dependency.kind)
                        && existing.display().equals(dependency.display())) {
                    return false;
                }
            }
            Map<Object, Object> raw = new LinkedHashMap<>();
            if ("project".equals(dependency.kind)) {
                raw.put("project", dependency.module);
            } else {
                raw.put("maven", dependency.group + ":" + dependency.artifact
                        + ":" + dependency.version);
            }
            if (dependency.scope != null && !dependency.scope.isBlank()) {
                raw.put("scope", dependency.scope);
            }
            @SuppressWarnings("unchecked")
            List<Object> deps = (List<Object>) module.computeIfAbsent("dependencies",
                    k -> new ArrayList<Object>());
            deps.add(raw);
            return true;
        }

        /** Retire une dépendance (identifiée par sa vue — retour : retirée ?). */
        public boolean removeDependency(String moduleName, Dependency target) {
            Map<String, Object> module = requireModule(moduleName);
            List<Object> deps = listOrEmpty(module.get("dependencies"));
            for (Object raw : deps) {
                if (raw instanceof Map && sameDependency((Map<?, ?>) raw, target)) {
                    deps.remove(raw);
                    return true;
                }
            }
            return false;
        }

        /** Change la version d'une dépendance Maven (retour : trouvée ?). */
        public boolean setDependencyVersion(String moduleName, String group,
                String artifact, String newVersion) {
            for (Object raw : listOrEmpty(requireModule(moduleName).get("dependencies"))) {
                if (raw instanceof Map) {
                    String maven = asString(((Map<?, ?>) raw).get("maven"));
                    if (maven != null && maven.startsWith(group + ":" + artifact + ":")) {
                        ((Map<Object, Object>) raw).put("maven",
                                group + ":" + artifact + ":" + newVersion);
                        return true;
                    }
                }
            }
            return false;
        }

        /** Écrit le modèle entier (compact, comme le moteur) de façon atomique. */
        public void writeAtomically() throws IOException {
            String text = JsonWriter.write(root);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        /**
         * Champ brut de la racine (chemin pointé {@code a.b}), rendu en texte — le
         * test de fidélité prouve que les champs inconnus survivent aux éditions.
         */
        public String champRacine(String chemin) {
            Object value = root;
            for (String step : chemin.split("\\.")) {
                if (!(value instanceof Map<?, ?> map)) {
                    return null;
                }
                value = map.get(step);
            }
            if (value == null) {
                return null;
            }
            return value instanceof String text ? text : JsonWriter.write(value);
        }

        /** Champ brut d'un module, rendu en texte (fidélité des champs inconnus). */
        public String champModule(String moduleName, String field) {
            Map<String, Object> module = moduleMap(moduleName);
            Object value = module == null ? null : module.get(field);
            if (value == null) {
                return null;
            }
            return value instanceof String text ? text : JsonWriter.write(value);
        }

        // ---- interne : accès à l'arbre -------------------------------------

        private Map<String, Object> moduleMap(String name) {
            for (Object raw : listOrEmpty(root.get("modules"))) {
                if (raw instanceof Map && name.equals(((Map<?, ?>) raw).get("name"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> module = (Map<String, Object>) raw;
                    return module;
                }
            }
            return null;
        }

        private Map<String, Object> requireModule(String name) {
            Map<String, Object> module = moduleMap(name);
            if (module == null) {
                throw new IllegalArgumentException("Module inconnu : " + name);
            }
            return module;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> androidOf(Map<String, Object> module) {
            Object raw = module.get("android");
            if (raw instanceof Map) {
                return (Map<String, Object>) raw;
            }
            Map<String, Object> created = new LinkedHashMap<>();
            module.put("android", created);
            return created;
        }

        private List<Object> mutableList(String field) {
            Object raw = root.get(field);
            if (raw instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) raw;
                return list;
            }
            List<Object> created = new ArrayList<>();
            root.put(field, created);
            return created;
        }

        private ModuleView moduleView(Map<String, Object> module) {
            String name = asString(module.get("name"));
            String type = orDefault(asString(module.get("type")), "java-library");
            String language = orDefault(asString(module.get("language")), "java");
            int javaRelease = asInt(module.get("javaRelease"), 17);
            List<String> sources = effectiveSources(module);
            List<String> resources = module.containsKey("resources")
                    ? stringList(module.get("resources"))
                    : List.of("src/main/resources");
            List<Dependency> dependencies = new ArrayList<>();
            for (Object raw : listOrEmpty(module.get("dependencies"))) {
                if (raw instanceof Map) {
                    dependencies.add(dependencyView((Map<?, ?>) raw));
                }
            }
            Optional<AndroidFacet> android = Optional.empty();
            if (module.get("android") instanceof Map<?, ?> facet) {
                Map<?, ?> features = facet.get("buildFeatures") instanceof Map<?, ?> f
                        ? f : Map.of();
                android = Optional.of(new AndroidFacet(
                        asString(facet.get("manifest")),
                        stringList(facet.get("res")),
                        asInt(facet.get("minSdk"), -1),
                        asInt(facet.get("targetSdk"), -1),
                        asString(facet.get("namespace")),
                        asInt(facet.get("versionCode"), -1),
                        asString(facet.get("versionName")),
                        Boolean.TRUE.equals(features.get("buildConfig")),
                        Boolean.TRUE.equals(features.get("viewBinding"))));
            }
            return new ModuleView(name, type, language, javaRelease, sources, resources,
                    List.copyOf(dependencies), android);
        }

        private static Dependency dependencyView(Map<?, ?> raw) {
            String project = asString(raw.get("project"));
            String maven = asString(raw.get("maven"));
            String scope = asString(raw.get("scope"));
            if (project != null) {
                return new Dependency("project", project, null, null, null,
                        orDefault(scope, "implementation"));
            }
            String[] parts = maven == null ? new String[0] : maven.split(":");
            return new Dependency("maven", null,
                    parts.length > 0 ? parts[0] : null,
                    parts.length > 1 ? parts[1] : null,
                    parts.length > 2 ? parts[2] : null,
                    orDefault(scope, "implementation"));
        }

        private static boolean sameDependency(Map<?, ?> raw, Dependency target) {
            if ("project".equals(target.kind)) {
                return target.module.equals(asString(raw.get("project")));
            }
            String maven = asString(raw.get("maven"));
            return maven != null && maven.equals(target.group + ":" + target.artifact
                    + ":" + target.version);
        }

        private static List<String> effectiveSources(Map<String, Object> module) {
            // Sémantique du moteur : une clé "sources" PRÉSENTE (même vide) fait
            // foi — seule son ABSENCE appelle la convention (src/main/java|kotlin).
            if (module.containsKey("sources")) {
                return stringList(module.get("sources"));
            }
            return defaultSources(module);
        }

        private static List<String> defaultSources(Map<String, Object> module) {
            return "kotlin".equals(asString(module.get("language")))
                    ? List.of("src/main/kotlin") : List.of("src/main/java");
        }
    }

    // ---- Petits outils de conversion -----------------------------------------

    private static String asString(Object raw) {
        return raw instanceof String text ? text : null;
    }

    private static int asInt(Object raw, int fallback) {
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static List<Object> listOrEmpty(Object raw) {
        if (raw instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) list;
            return result;
        }
        return List.of();
    }

    private static List<String> stringList(Object raw) {
        List<String> result = new ArrayList<>();
        for (Object item : listOrEmpty(raw)) {
            if (item instanceof String text) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    // ---- Parseur JSON maison (règle : aucune bibliothèque par module) --------

    static final class JsonSyntax extends Exception {
        JsonSyntax(String message) {
            super(message);
        }
    }

    /** Parseur JSON : objets ordonnés, tableaux, chaînes échappées, entiers/décimaux. */
    static final class JsonParser {

        static Object parse(String text) throws JsonSyntax {
            Cursor cursor = new Cursor(text);
            cursor.skipWhitespace();
            Object value = cursor.readValue();
            cursor.skipWhitespace();
            if (!cursor.atEnd()) {
                throw cursor.error("des données suivent la valeur");
            }
            return value;
        }

        private static final class Cursor {
            private final String text;
            private int position;

            Cursor(String text) {
                this.text = text;
            }

            boolean atEnd() {
                return position >= text.length();
            }

            JsonSyntax error(String reason) {
                return new JsonSyntax("position " + position + " : " + reason);
            }

            void skipWhitespace() {
                while (position < text.length()) {
                    char c = text.charAt(position);
                    if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                        return;
                    }
                    position++;
                }
            }

            char peek() throws JsonSyntax {
                if (atEnd()) {
                    throw error("fin inattendue");
                }
                return text.charAt(position);
            }

            void expect(char expected) throws JsonSyntax {
                char c = peek();
                if (c != expected) {
                    throw error("'" + expected + "' attendu, '" + c + "' trouvé");
                }
                position++;
            }

            Object readValue() throws JsonSyntax {
                char c = peek();
                switch (c) {
                    case '{':
                        return readObject();
                    case '[':
                        return readArray();
                    case '"':
                        return readString();
                    default:
                        return readLiteral();
                }
            }

            private Map<String, Object> readObject() throws JsonSyntax {
                expect('{');
                Map<String, Object> object = new LinkedHashMap<>();
                skipWhitespace();
                if (peek() == '}') {
                    position++;
                    return object;
                }
                while (true) {
                    skipWhitespace();
                    String key = readString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    object.put(key, readValue());
                    skipWhitespace();
                    char c = peek();
                    if (c == ',') {
                        position++;
                    } else if (c == '}') {
                        position++;
                        return object;
                    } else {
                        throw error("',' ou '}' attendu");
                    }
                }
            }

            private List<Object> readArray() throws JsonSyntax {
                expect('[');
                List<Object> array = new ArrayList<>();
                skipWhitespace();
                if (peek() == ']') {
                    position++;
                    return array;
                }
                while (true) {
                    skipWhitespace();
                    array.add(readValue());
                    skipWhitespace();
                    char c = peek();
                    if (c == ',') {
                        position++;
                    } else if (c == ']') {
                        position++;
                        return array;
                    } else {
                        throw error("',' ou ']' attendu");
                    }
                }
            }

            private String readString() throws JsonSyntax {
                expect('"');
                StringBuilder out = new StringBuilder();
                while (true) {
                    if (atEnd()) {
                        throw error("chaîne non fermée");
                    }
                    char c = text.charAt(position++);
                    if (c == '"') {
                        return out.toString();
                    }
                    if (c == '\\') {
                        if (atEnd()) {
                            throw error("échappement non fermé");
                        }
                        char escaped = text.charAt(position++);
                        switch (escaped) {
                            case '"' -> out.append('"');
                            case '\\' -> out.append('\\');
                            case '/' -> out.append('/');
                            case 'b' -> out.append('\b');
                            case 'f' -> out.append('\f');
                            case 'n' -> out.append('\n');
                            case 'r' -> out.append('\r');
                            case 't' -> out.append('\t');
                            case 'u' -> {
                                if (position + 4 > text.length()) {
                                    throw error("\\u tronqué");
                                }
                                String hex = text.substring(position, position + 4);
                                try {
                                    out.append((char) Integer.parseInt(hex, 16));
                                } catch (NumberFormatException e) {
                                    throw error("\\u invalide : " + hex);
                                }
                                position += 4;
                            }
                            default -> throw error("échappement inconnu : \\" + escaped);
                        }
                    } else {
                        out.append(c);
                    }
                }
            }

            private Object readLiteral() throws JsonSyntax {
                int start = position;
                while (!atEnd()) {
                    char c = text.charAt(position);
                    if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t'
                            || c == '\n' || c == '\r') {
                        break;
                    }
                    position++;
                }
                String literal = text.substring(start, position);
                switch (literal) {
                    case "true":
                        return Boolean.TRUE;
                    case "false":
                        return Boolean.FALSE;
                    case "null":
                        return null;
                    default:
                        try {
                            return Long.parseLong(literal);
                        } catch (NumberFormatException notLong) {
                            try {
                                return Double.parseDouble(literal);
                            } catch (NumberFormatException notDouble) {
                                throw error("littéral inconnu : " + literal);
                            }
                        }
                }
            }
        }
    }

    // ---- Écrivain JSON maison ------------------------------------------------

    /** Écriture compacte (même style que le moteur), échappements complets. */
    static final class JsonWriter {

        static String write(Object value) {
            StringBuilder out = new StringBuilder(256);
            writeValue(value, out);
            return out.toString();
        }

        private static void writeValue(Object value, StringBuilder out) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String text) {
                writeString(text, out);
            } else if (value instanceof Boolean || value instanceof Long
                    || value instanceof Integer) {
                out.append(value);
            } else if (value instanceof Double || value instanceof Float) {
                out.append(value);
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    writeValue(entry.getValue(), out);
                }
                out.append('}');
            } else if (value instanceof Iterable<?> list) {
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeValue(item, out);
                }
                out.append(']');
            } else {
                throw new IllegalArgumentException(
                        "Type non sérialisable : " + value.getClass());
            }
        }

        private static void writeString(String text, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }
}
