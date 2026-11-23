package jo.joide.workbench;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Console de build (dock de l'atelier) : le modèle des lignes affichées,
 * indépendant d'Android pour rester testable sur JVM.
 *
 * <p>La console n'est pas un vulgaire tampon de texte : chaque ligne a une
 * {@link Kind nature} (tâche réussie, échec, avertissement, progression…)
 * que la coquille traduit en couleur. Les tâches vivent : une tâche
 * {@code STARTED} occupe une ligne « en cours » que l'état terminal
 * ({@code SUCCEEDED}, {@code FAILED}…) <b>remplace en place</b> — comme la
 * console d'un IDE, pas un journal qui défile à l'infini. La progression
 * globale est une ligne collante mise à jour en place (jamais de spam
 * « 3/12 · 4/12 · 5/12 »), que la fin du build transforme en ligne de
 * résultat.</p>
 *
 * <p>La capacité est bornée : les lignes les plus anciennes disparaissent,
 * le compte {@link #trimmedTotal()} reste honnête pour l'affichage (« … N
 * lignes antérieures masquées ») et le presse-papiers.</p>
 *
 * <p>Le rendu est incrémental : chaque appel produit des {@link Mutation}
 * (ajout, remplacement en place, élagage) que la vue rejoue sur son tampon
 * — jamais de reconstruction complète à chaque événement. Fil UI
 * uniquement (les événements de build y sont déjà acheminés).</p>
 */
public final class ConsoleBuffer {

    /** Capacité par défaut : 2000 lignes bornent la mémoire sans rogner un build réel. */
    public static final int DEFAULT_CAPACITY = 2000;

    /** Nature d'une ligne — la coquille choisit la couleur et le marqueur. */
    public enum Kind {
        /** En-tête de run (« &gt; build Forge · 14:32 »). */
        HEADER,
        /** Ligne d'information. */
        INFO,
        /** Journal bavard, atténué. */
        DEBUG,
        /** Ligne de vie composée par le MOTEUR (protocole 4) : tâche, verdict. */
        LIFECYCLE,
        /** Avertissement. */
        WARNING,
        /** Erreur sans tâche fautive identifiée. */
        ERROR,
        /** Tâche démarrée (remplacée en place par son état terminal). */
        TASK_RUNNING,
        /** Tâche réussie (avec sa durée réelle). */
        TASK_OK,
        /** Tâche échouée. */
        TASK_FAILED,
        /** Tâche éludée (SKIP). */
        TASK_SKIPPED,
        /** Tâche à jour, aucun travail refait. */
        TASK_UP_TO_DATE,
        /** Ligne collante de progression (remplacée par le résultat au finish). */
        PROGRESS,
        /** Bilan de build réussi. */
        SUCCESS,
        /** Bilan de build échoué. */
        FAILURE
    }

    /** Une ligne : identité stable (id) + nature et texte muables en place. */
    public static final class Entry {
        public final long id;
        public Kind kind;
        public String text;
        private boolean dropped;

        Entry(long id, Kind kind, String text) {
            this.id = id;
            this.kind = kind;
            this.text = text;
        }

        /** Faux dès que l'élagage a retiré la ligne (gardes O(1)). */
        public boolean isLive() {
            return !dropped;
        }
    }

    /** Mutation à rejouer par la vue : ajout, remplacement en place, élagage. */
    public static final class Mutation {

        /** Type de mutation. */
        public enum Type { APPEND, UPDATE, TRIM }

        /** Type. */
        public final Type type;
        /** Ligne concernée (APPEND et UPDATE ; null pour TRIM). */
        public final Entry entry;
        /** Nombre de lignes élaguées cette fois (TRIM seul). */
        public final int count;

        private Mutation(Type type, Entry entry, int count) {
            this.type = type;
            this.entry = entry;
            this.count = count;
        }
    }

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Map<String, Entry> runningTasks = new HashMap<>();
    private Entry progressEntry;
    private long nextId = 1;
    private int trimmedTotal;
    private final List<Mutation> pending = new ArrayList<>();

    /** Console bornée à {@code capacity} lignes (minimum 10). */
    public ConsoleBuffer(int capacity) {
        this.capacity = Math.max(10, capacity);
    }

    /** Vide tout (nouveau build : la console repart propre). */
    public void clear() {
        for (Entry e : entries) {
            e.dropped = true;
        }
        entries.clear();
        runningTasks.clear();
        progressEntry = null;
        trimmedTotal = 0;
        // Les mutations en attente décrivent un état mort : la vue
        // réinitialise son tampon depuis snapshot(), rien à rejouer.
        pending.clear();
    }

    /** En-tête de run. */
    public void header(String text) {
        append(Kind.HEADER, text);
    }

    /** Ligne simple. */
    public void line(Kind kind, String text) {
        append(kind, text);
    }

    /**
     * État d'une tâche du graphe. {@code TASK_RUNNING} crée (ou rafraîchit)
     * la ligne « en cours » de la tâche ; un état terminal remplace cette
     * ligne en place — ou s'ajoute seul si le moteur n'émet pas de STARTED.
     * Une tâche relancée dans le même build repart sur une nouvelle ligne.
     */
    public void task(String task, Kind kind, String text) {
        Entry live = runningTasks.remove(task);
        if (live != null && live.isLive()) {
            live.kind = kind;
            live.text = text;
            pending.add(new Mutation(Mutation.Type.UPDATE, live, 0));
            if (kind == Kind.TASK_RUNNING) {
                runningTasks.put(task, live);
            }
            return;
        }
        Entry entry = append(kind, text);
        if (kind == Kind.TASK_RUNNING) {
            runningTasks.put(task, entry);
        }
    }

    /**
     * Progression globale : une seule ligne collante, mise à jour en place.
     */
    public void progress(String text) {
        if (progressEntry != null && progressEntry.isLive()) {
            progressEntry.text = text;
            pending.add(new Mutation(Mutation.Type.UPDATE, progressEntry, 0));
            return;
        }
        progressEntry = append(Kind.PROGRESS, text);
    }

    /**
     * Bilan de build : remplace la ligne de progression en place (elle
     * devient le résultat), ou s'ajoute si aucune progression n'a été émise.
     */
    public void finished(Kind bilan, String text) {
        if (progressEntry != null && progressEntry.isLive()) {
            progressEntry.kind = bilan;
            progressEntry.text = text;
            pending.add(new Mutation(Mutation.Type.UPDATE, progressEntry, 0));
            progressEntry = null;
            return;
        }
        append(bilan, text);
    }

    /** Mutations à rejouer (la vue les draine après chaque événement). */
    public List<Mutation> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Mutation> drained = new ArrayList<>(pending);
        pending.clear();
        return drained;
    }

    /** Copie des lignes courantes (rendu initial de la vue). */
    public List<Entry> snapshot() {
        List<Entry> copy = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            copy.add(new Entry(e.id, e.kind, e.text));
        }
        return copy;
    }

    /** Nombre de lignes réellement en tampon. */
    public int size() {
        return entries.size();
    }

    /** Lignes élaguées depuis le début (affichage et presse-papiers honnêtes). */
    public int trimmedTotal() {
        return trimmedTotal;
    }

    /** Texte brut (presse-papiers), chaque ligne terminée par un saut. */
    public String plainText() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.text).append('\n');
        }
        return sb.toString();
    }

    // ---- interne -----------------------------------------------------------

    private Entry append(Kind kind, String text) {
        Entry entry = new Entry(nextId++, kind, text);
        entries.addLast(entry);
        pending.add(new Mutation(Mutation.Type.APPEND, entry, 0));
        trim();
        return entry;
    }

    private void trim() {
        int excess = entries.size() - capacity;
        if (excess <= 0) {
            return;
        }
        for (int i = 0; i < excess; i++) {
            Entry oldest = entries.pollFirst();
            oldest.dropped = true;
            trimmedTotal++;
            runningTasks.values().remove(oldest);
            if (progressEntry == oldest) {
                progressEntry = null;
            }
        }
        pending.add(new Mutation(Mutation.Type.TRIM, null, excess));
    }
}
