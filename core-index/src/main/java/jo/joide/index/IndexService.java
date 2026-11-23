package jo.joide.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jo.joide.outline.Outline;
import jo.joide.workbench.BuildActivity;

/**
 * Service d'index du projet (lot L10, d'après la référence — REF-126/127) :
 * à l'ouverture d'un projet, un pass construit l'index que les fournisseurs de
 * langue (structure, recherche de symboles, complétion à venir) consomment.
 *
 * <p>Deux phases comme la référence : <b>Bibliothèques &amp; SDK</b> — les noms de
 * classes des jars du classpath résolu (lecture des entrées zip, sans parsing de
 * bytecode ; empreinte par jar taille+mtime, un jar inchangé n'est pas relu) —
 * puis <b>Sources du projet</b> — les symboles de déclaration par l'analyseur
 * tolérant existant ({@link Outline}), empreintes par fichier taille+mtime : seuls
 * les fichiers dirty sont relus. L'indexation CÈDE au build : le pass attend que
 * plus aucune compile ne tourne ({@link BuildActivity#awaitIdle}) — un build
 * n'est jamais ralenti par l'arrière-plan.</p>
 *
 * <p>Incrémental : {@link #reindexSource(Path, String)} remplace les entrées du
 * SEUL fichier édité (à la sauvegarde — coût O(fichier), pas O(projet)).
 * Annulable : un pass coupé laisse un index utilisable mais NON prêt (les
 * requêtes rendent vide — l'appelant retombe sur la recherche plein texte,
 * jamais de faux résultats). Stockage persistant borné sous
 * {@code <projet>/.joide/index/}, écritures atomiques ; un index disque corrompu
 * est reconstruit.</p>
 */
public final class IndexService {

    /** Natures de symbole indexées (les déclarations — pas package/imports). */
    private static final Set<Outline.Kind> DÉCLARATIONS = Set.of(Outline.Kind.CLASS,
            Outline.Kind.INTERFACE, Outline.Kind.ENUM, Outline.Kind.ANNOTATION_TYPE,
            Outline.Kind.FIELD, Outline.Kind.CONSTRUCTOR, Outline.Kind.METHOD);

    /** Périmètre d'un pass : racines sources + jars du classpath résolu. */
    public record Scope(List<Path> sourceRoots, List<Path> libraryJars) {}

    /** Phase du pass (affichée par la puce et le dialogue de statut). */
    public enum Phase { LIBRAIRIES, SOURCES }

    /** Un symbole trouvé par la recherche. */
    public record SymbolHit(String name, Outline.Kind kind, String signature,
            Path file, int line) {}

    /** Une classe du classpath trouvée par préfixe. */
    public record ClassHit(String fqn, Path jar) {}

    /** Statistiques du dernier pass (réutilisé/reconstruit — honnêtes). */
    public record Stats(int jarsScannés, int jarsRéutilisés, int fichiersIndexés,
            int fichiersRéutilisés, int symboles, int classes, long ms) {}

    /** Statut observable (la puce et le dialogue s'y abonnent). */
    public static final class Status {
        private volatile boolean building;
        private volatile Phase phase;
        private volatile double fraction;
        private volatile String message = "";
        private volatile int processed;
        private volatile int total;
        private volatile Stats stats = new Stats(0, 0, 0, 0, 0, 0, 0);
        private volatile boolean prêt;

        /** Un pass tourne-t-il ? */
        public boolean building() {
            return building;
        }

        /** Phase courante. */
        public Phase phase() {
            return phase;
        }

        /** Progression 0..1 (ou -1 : indéterminée). */
        public double fraction() {
            return fraction;
        }

        /** Message honnête (phase + compteur). */
        public String message() {
            return message;
        }

        /** Compteur de la phase source (fichiers). */
        public int processed() {
            return processed;
        }

        /** Total attendu de la phase source. */
        public int total() {
            return total;
        }

        /** Statistiques du dernier pass. */
        public Stats stats() {
            return stats;
        }

        /**
         * Index prêt : un pass complet s'est terminé (non coupé). Tant que faux,
         * les requêtes rendent vide — le repli honnête, pas de faux résultats.
         */
        public boolean ready() {
            return prêt && !building;
        }
    }

    private final Path projectRoot;
    private final Path storeDir;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Status status = new Status();
    private final List<Consumer<Status>> listeners = new ArrayList<>();
    private final AtomicBoolean passEnCours = new AtomicBoolean();

    // ---- tables en mémoire (relues du disque au premier pass) ------------------
    private final Map<Path, Long> fichierIds = new LinkedHashMap<>(); // chemin relatif → id
    private final Map<Long, Empreinte> empreintes = new LinkedHashMap<>();
    private final List<Symbole> symboles = new ArrayList<>();
    private final Map<Path, List<String>> classesParJar = new LinkedHashMap<>();
    private final Map<Path, Empreinte> empreinteJar = new LinkedHashMap<>();
    private long prochainId = 1;

    private record Empreinte(long taille, long mtime) {}

    private record Symbole(long fichier, Outline.Kind nature, String nom, String signature,
            int ligne) {}

    /** Service sur un projet (stockage sous {@code <projet>/.joide/index}). */
    public IndexService(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.storeDir = projectRoot.resolve(".joide").resolve("index");
    }

    /** Ajoute un auditeur de statut (appelé sur le fil du pass). */
    public Runnable observe(Consumer<Status> listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
        return () -> {
            synchronized (listeners) {
                listeners.remove(listener);
            }
        };
    }

    /** Statut courant. */
    public Status status() {
        return status;
    }

    /** Annule le pass en cours (l'index reste lisible mais non prêt). */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Pass complet : bibliothèques puis sources, sur l'exécuteur donné (basse
     * priorité — l'appelant choisit son fil). Attend d'abord que le build
     * éventuel se termine (borné à 60 s : au timeout, le pass part quand même).
     *
     * @param scope    périmètre (racines sources + jars).
     * @param executor exécuteur du pass.
     * @param activity activité de build (null : pas d'attente — tests).
     * @return avenir du statut final (jamais d'échec : un jar illisible est
     *         sauté — pass partiel honnête, non prêt si coupé).
     */
    public CompletableFuture<Status> ensureUpToDate(Scope scope, Executor executor,
            BuildActivity activity) {
        if (!passEnCours.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(status); // un pass à la fois
        }
        cancelled.set(false);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (activity != null) {
                    try {
                        activity.awaitIdle(Duration.ofSeconds(60).toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                synchronized (this) {
                    relireDepuisDisque();
                    runPass(scope);
                }
                return status;
            } finally {
                passEnCours.set(false);
            }
        }, executor);
    }

    /** Le pass proprement dit (appelé sous verrou). */
    private void runPass(Scope scope) {
        status.building = true;
        status.prêt = false;
        long départ = System.currentTimeMillis();
        jarsScannésTotal = 0;
        jarsRéutilisésTotal = 0;
        int fichiersIndexés = 0;
        int fichiersRéutilisés = 0;
        try {
            indexerBibliothèques(scope.libraryJars());
            if (!cancelled.get()) {
                int[] compteurs = indexerSources(scope.sourceRoots());
                fichiersIndexés = compteurs[0];
                fichiersRéutilisés = compteurs[1];
            }
            if (!cancelled.get()) {
                status.fraction = 1.0;
                status.message = "Index prêt";
                status.prêt = true;
            } else {
                status.message = "Indexation interrompue"; // partiel, NON prêt
            }
            status.stats = new Stats(jarsScannésTotal, jarsRéutilisésTotal,
                    fichiersIndexés, fichiersRéutilisés, symboles.size(),
                    compterClasses(), System.currentTimeMillis() - départ);
            try {
                persister(); // survit au redémarrage (écriture atomique)
            } catch (IOException diskError) {
                // L'index mémoire est juste ; le prochain pass réécrira.
            }
        } finally {
            status.building = false;
            publier();
        }
    }

    /**
     * Ré-indexation d'un seul fichier (à la sauvegarde) — remplace ses entrées,
     * coût O(fichier). Persiste immédiatement (écriture atomique, petit).
     */
    public synchronized void reindexSource(Path file, String text) {
        Path relatif = projectRoot.relativize(file);
        Long existante = fichierIds.get(relatif);
        final long id = existante != null ? existante : prochainId++;
        if (existante == null) {
            fichierIds.put(relatif, id);
        }
        empreintes.put(id, new Empreinte(taille(file), lastModified(file)));
        symboles.removeIf(s -> s.fichier() == id);
        for (Outline.Symbol symbol : Outline.javaOutline(text)) {
            if (DÉCLARATIONS.contains(symbol.kind())) {
                symboles.add(new Symbole(id, symbol.kind(), symbol.name(),
                        symbol.signature(), symbol.line()));
            }
        }
        try {
            persister();
        } catch (IOException diskError) {
            // L'index mémoire reste juste ; le disque sera réécrit au prochain pass.
        }
    }

    /**
     * Recherche de symboles par préfixe (insensible à la casse, contient en
     * second). Vide tant que l'index n'est pas prêt — l'appelant retombe sur la
     * recherche plein texte, jamais de faux résultats.
     */
    public synchronized List<SymbolHit> searchSymbols(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || !status.ready()) {
            return List.of();
        }
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<SymbolHit> startsWith = new ArrayList<>();
        List<SymbolHit> contains = new ArrayList<>();
        for (Symbole symbole : symboles) {
            String nom = symbole.nom().toLowerCase(Locale.ROOT);
            if (nom.startsWith(needle)) {
                Path fichier = cheminDe(symbole.fichier());
                if (fichier != null) {
                    startsWith.add(new SymbolHit(symbole.nom(), symbole.nature(),
                            symbole.signature(), fichier, symbole.ligne()));
                }
            } else if (nom.contains(needle)) {
                Path fichier = cheminDe(symbole.fichier());
                if (fichier != null) {
                    contains.add(new SymbolHit(symbole.nom(), symbole.nature(),
                            symbole.signature(), fichier, symbole.ligne()));
                }
            }
        }
        startsWith.sort(Comparator.comparing((SymbolHit hit) ->
                hit.name().toLowerCase(Locale.ROOT)));
        contains.sort(Comparator.comparing((SymbolHit hit) ->
                hit.name().toLowerCase(Locale.ROOT)));
        List<SymbolHit> hits = new ArrayList<>(startsWith);
        hits.addAll(contains);
        return hits.subList(0, Math.min(hits.size(), limit));
    }

    /** Recherche de classes du classpath par préfixe de nom simple ou de FQN. */
    public synchronized List<ClassHit> searchClasses(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || !status.ready()) {
            return List.of();
        }
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<ClassHit> hits = new ArrayList<>();
        for (Map.Entry<Path, List<String>> jar : classesParJar.entrySet()) {
            for (String fqn : jar.getValue()) {
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1)
                        .toLowerCase(Locale.ROOT);
                if (simple.startsWith(needle)
                        || fqn.toLowerCase(Locale.ROOT).startsWith(needle)) {
                    hits.add(new ClassHit(fqn, jar.getKey()));
                    if (hits.size() >= limit) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }

    /** Invalide tout (action Re-index) : les tables repartent vides. */
    public synchronized void invalidate() {
        fichierIds.clear();
        empreintes.clear();
        symboles.clear();
        classesParJar.clear();
        empreinteJar.clear();
        prochainId = 1;
        status.prêt = false;
        status.stats = new Stats(0, 0, 0, 0, 0, 0, 0);
        try {
            if (Files.isDirectory(storeDir)) {
                try (Stream<Path> walk = Files.walk(storeDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // le prochain pass réécrira
                        }
                    });
                }
            }
        } catch (IOException e) {
            // idem
        }
    }

    // ---- Pass ----------------------------------------------------------------

    private void indexerBibliothèques(List<Path> jars) {
        status.phase = Phase.LIBRAIRIES;
        status.fraction = -1; // indéterminée jusqu'au premier jar
        status.message = "Bibliothèques & SDK — " + jars.size() + " artefact(s)";
        publier();
        int fait = 0;
        for (Path jar : jars) {
            if (cancelled.get()) {
                return;
            }
            fait++;
            status.fraction = jars.isEmpty() ? 1.0 : (double) fait / jars.size();
            status.message = "Bibliothèques & SDK — " + fait + "/" + jars.size();
            publier();
            if (!Files.isRegularFile(jar)) {
                continue; // jar annoncé mais absent : sauté honnêtement
            }
            Empreinte fraîche = new Empreinte(taille(jar), lastModified(jar));
            if (fraîche.equals(empreinteJar.get(jar)) && classesParJar.containsKey(jar)) {
                jarsRéutilisésTotal++;
            } else {
                classesParJar.put(jar, lireNomsDeClasses(jar));
                empreinteJar.put(jar, fraîche);
                jarsScannésTotal++;
            }
        }
    }

    /** @return {fichiersIndexés, fichiersRéutilisés}. */
    private int[] indexerSources(List<Path> racines) {
        status.phase = Phase.SOURCES;
        List<Path> fichiers = new ArrayList<>();
        for (Path racine : racines) {
            if (!Files.isDirectory(racine)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(racine)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java"))
                        .forEach(fichiers::add);
            } catch (IOException e) {
                // Racine illisible : pass partiel honnête, pas d'échec.
            }
        }
        status.total = fichiers.size();
        status.processed = 0;
        status.fraction = 0;
        status.message = "Sources du projet — 0/" + fichiers.size();
        publier();
        int indexés = 0;
        int réutilisés = 0;
        int fait = 0;
        for (Path fichier : fichiers) {
            if (cancelled.get()) {
                return new int[] {indexés, réutilisés};
            }
            int[] résultat = indexerFichier(fichier);
            indexés += résultat[0];
            réutilisés += résultat[1];
            fait++;
            status.processed = fait;
            status.fraction = fichiers.isEmpty() ? 1.0 : (double) fait / fichiers.size();
            if (fait % 16 == 0 || fait == fichiers.size()) { // anti-churn (référence)
                status.message = "Sources du projet — " + fait + "/" + fichiers.size();
                publier();
            }
        }
        return new int[] {indexés, réutilisés};
    }

    private int jarsScannésTotal;
    private int jarsRéutilisésTotal;

    /** @return {fichierIndexé (0/1), fichierRéutilisé (0/1)}. */
    private int[] indexerFichier(Path fichier) {
        Path relatif = projectRoot.relativize(fichier);
        Empreinte fraîche = new Empreinte(taille(fichier), lastModified(fichier));
        Long connue = fichierIds.get(relatif);
        if (connue != null && fraîche.equals(empreintes.get(connue))) {
            return new int[] {0, 1}; // inchangé : rien à relire
        }
        final long id = connue != null ? connue : prochainId++;
        if (connue == null) {
            fichierIds.put(relatif, id);
        }
        empreintes.put(id, fraîche);
        symboles.removeIf(s -> s.fichier() == id);
        try {
            String text = Files.readString(fichier, StandardCharsets.UTF_8);
            for (Outline.Symbol symbol : Outline.javaOutline(text)) {
                if (DÉCLARATIONS.contains(symbol.kind())) {
                    symboles.add(new Symbole(id, symbol.kind(), symbol.name(),
                            symbol.signature(), symbol.line()));
                }
            }
            return new int[] {1, 0};
        } catch (IOException e) {
            return new int[] {0, 0}; // illisible : tolérant, pass continue
        }
    }

    private List<String> lireNomsDeClasses(Path jar) {
        List<String> noms = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String nom = entry.getName();
                if (nom.endsWith(".class") && !nom.endsWith("module-info.class")) {
                    noms.add(nom.substring(0, nom.length() - ".class".length())
                            .replace('/', '.'));
                }
            }
        } catch (IOException e) {
            // Jar illisible : classes absentes, jamais d'échec du pass (référence).
        }
        return noms;
    }

    private int compterClasses() {
        return classesParJar.values().stream().mapToInt(List::size).sum();
    }

    // ---- Stockage (texte lisible, écriture atomique) -------------------------

    private void persister() throws IOException {
        Files.createDirectories(storeDir);
        StringBuilder files = new StringBuilder();
        for (Map.Entry<Path, Long> entry : fichierIds.entrySet()) {
            Empreinte empreinte = empreintes.get(entry.getValue());
            if (empreinte != null) {
                files.append(entry.getValue()).append('\t')
                        .append(empreinte.taille()).append('\t')
                        .append(empreinte.mtime()).append('\t')
                        .append(échapper(entry.getKey().toString())).append('\n');
            }
        }
        écrireAtomiquement(storeDir.resolve("fichiers.txt"), files.toString());
        StringBuilder symbols = new StringBuilder();
        for (Symbole s : symboles) {
            symbols.append(s.fichier()).append('\t').append(s.nature().ordinal()).append('\t')
                    .append(échapper(s.nom())).append('\t').append(s.ligne()).append('\t')
                    .append(échapper(s.signature())).append('\n');
        }
        écrireAtomiquement(storeDir.resolve("symboles.txt"), symbols.toString());
        StringBuilder jars = new StringBuilder();
        for (Map.Entry<Path, Empreinte> entry : empreinteJar.entrySet()) {
            jars.append(entry.getValue().taille()).append('\t')
                    .append(entry.getValue().mtime()).append('\t')
                    .append(échapper(entry.getKey().toString())).append('\n');
        }
        écrireAtomiquement(storeDir.resolve("jars.txt"), jars.toString());
        StringBuilder classes = new StringBuilder();
        Map<Path, Integer> position = new LinkedHashMap<>();
        int index = 0;
        for (Path jar : empreinteJar.keySet()) {
            position.put(jar, index++);
        }
        for (Map.Entry<Path, List<String>> entry : classesParJar.entrySet()) {
            Integer pos = position.get(entry.getKey());
            if (pos == null) {
                continue;
            }
            for (String fqn : entry.getValue()) {
                classes.append(pos).append('\t').append(échapper(fqn)).append('\n');
            }
        }
        écrireAtomiquement(storeDir.resolve("classes.txt"), classes.toString());
    }

    private void relireDepuisDisque() {
        try {
            if (!Files.isDirectory(storeDir)) {
                return;
            }
            List<String> jars = lignes(storeDir.resolve("jars.txt"));
            List<Path> cheminsJars = new ArrayList<>();
            for (String ligne : jars) {
                String[] champs = ligne.split("\t", 3);
                if (champs.length == 3) {
                    cheminsJars.add(Path.of(déséchapper(champs[2])));
                }
            }
            Map<Integer, List<String>> classesParPosition = new LinkedHashMap<>();
            for (String ligne : lignes(storeDir.resolve("classes.txt"))) {
                int tab = ligne.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                classesParPosition.computeIfAbsent(
                        Integer.parseInt(ligne.substring(0, tab)), k -> new ArrayList<>())
                        .add(déséchapper(ligne.substring(tab + 1)));
            }
            for (int i = 0; i < cheminsJars.size(); i++) {
                String[] champs = jars.get(i).split("\t", 3);
                classesParJar.put(cheminsJars.get(i),
                        classesParPosition.getOrDefault(i, List.of()));
                empreinteJar.put(cheminsJars.get(i), new Empreinte(
                        Long.parseLong(champs[0]), Long.parseLong(champs[1])));
            }
            for (String ligne : lignes(storeDir.resolve("fichiers.txt"))) {
                String[] champs = ligne.split("\t", 4);
                if (champs.length != 4) {
                    continue;
                }
                long id = Long.parseLong(champs[0]);
                fichierIds.put(Path.of(déséchapper(champs[3])), id); // chemin RELATIF
                empreintes.put(id, new Empreinte(Long.parseLong(champs[1]),
                        Long.parseLong(champs[2])));
                prochainId = Math.max(prochainId, id + 1);
            }
            Outline.Kind[] natures = Outline.Kind.values();
            for (String ligne : lignes(storeDir.resolve("symboles.txt"))) {
                String[] champs = ligne.split("\t", 5);
                if (champs.length < 4) {
                    continue;
                }
                int ordinal = Integer.parseInt(champs[1]);
                if (ordinal < 0 || ordinal >= natures.length) {
                    continue; // format futur : entrée ignorée, pas de crash
                }
                symboles.add(new Symbole(Long.parseLong(champs[0]), natures[ordinal],
                        déséchapper(champs[2]),
                        champs.length > 4 ? déséchapper(champs[4]) : "",
                        Integer.parseInt(champs[3])));
            }
        } catch (Exception e) {
            // Index disque corrompu : reconstruction complète au pass — jamais
            // de faux résultats (la référence réinitialise aussi).
            fichierIds.clear();
            empreintes.clear();
            symboles.clear();
            classesParJar.clear();
            empreinteJar.clear();
            prochainId = 1;
        }
    }

    // ---- Petits outils ---------------------------------------------------------

    private Path cheminDe(long id) {
        for (Map.Entry<Path, Long> entry : fichierIds.entrySet()) {
            if (entry.getValue() == id) {
                return projectRoot.resolve(entry.getKey());
            }
        }
        return null;
    }

    private void publier() {
        List<Consumer<Status>> copie;
        synchronized (listeners) {
            copie = new ArrayList<>(listeners);
        }
        for (Consumer<Status> listener : copie) {
            listener.accept(status);
        }
    }

    private static long taille(Path fichier) {
        try {
            return Files.size(fichier);
        } catch (IOException e) {
            return -1;
        }
    }

    private static long lastModified(Path fichier) {
        try {
            return Files.getLastModifiedTime(fichier).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<String> lignes(Path fichier) throws IOException {
        if (!Files.isRegularFile(fichier)) {
            return List.of();
        }
        return Files.readAllLines(fichier, StandardCharsets.UTF_8);
    }

    private void écrireAtomiquement(Path cible, String contenu) throws IOException {
        Path temp = cible.resolveSibling(cible.getFileName() + ".tmp");
        Files.writeString(temp, contenu, StandardCharsets.UTF_8);
        try {
            Files.move(temp, cible, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, cible, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String échapper(String texte) {
        return texte.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String déséchapper(String texte) {
        return texte.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
