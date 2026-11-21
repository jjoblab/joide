package jo.joide.app.drawer;

import androidx.lifecycle.ViewModel;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jo.joide.files.AndroidView;
import jo.joide.files.FileKind;
import jo.joide.files.FileTree;
import jo.joide.workbench.WorkspaceEvents;

/**
 * État partagé du tiroir gauche : chemin du projet — source unique pour les
 * onglets — bus d'événements de l'espace de travail, et depuis le Lot 2
 * l'état de l'arborescence (dossiers ouverts, listings, types affinés).
 * Portée : tiroir — survit aux changements d'onglet, à la rotation et au
 * va-et-vient vers l'écran Git (le fragment tiroir vit aussi longtemps que
 * l'atelier qui l'héberge).
 */
public class WorkspaceViewModel extends ViewModel {

    private String projectPath;
    private WorkspaceEvents events = new WorkspaceEvents();

    /** Dossiers ouverts de l'arbre (chemins relatifs ; « . » = racine). */
    private final Set<String> expandedDirs = new LinkedHashSet<>();

    /** Listings mis en cache par dossier relatif (invalidés par les événements). */
    private final Map<String, FileTree.Listing> listingCache = new HashMap<>();

    /** Types affinés par chemin relatif (l'affinage Java lit un en-tête). */
    private final Map<String, FileKind> kindCache = new HashMap<>();

    /** Comptes d'éléments par dossier replié (badges, Lot 3). */
    private final Map<String, FileTree.Count> countCache = new HashMap<>();

    /** Chaînes compactes par dossier de package (« com.example », Lot 3). */
    private final Map<String, FileTree.Chain> chainCache = new HashMap<>();

    /** Vue Android active (Lot 3) ; l'état survit aux rotations. */
    private boolean androidView;

    /** Squelette de la vue Android (Lot 3), en cache jusqu'à actualisation. */
    private List<AndroidView.Node> androidNodes;

    /** Attache le bus partagé de l'atelier (Lot 8) : un seul chemin de mise à jour. */
    public void attachEvents(WorkspaceEvents shared) {
        if (shared != null && shared != events) {
            this.events = shared;
        }
    }

    /** Pose le chemin du projet (une seule fois : l'atelier ne change pas). */
    public void init(String path) {
        if (projectPath == null) {
            projectPath = path;
        }
    }

    /** Chemin du projet ouvert ; null hors atelier. */
    /** Instant de la dernière synchro git (−1 : jamais synchronisé). */
    private long gitLastSyncMillis = -1L;

    public long gitLastSyncMillis() {
        return gitLastSyncMillis;
    }

    public void setGitLastSyncMillis(long value) {
        gitLastSyncMillis = value;
    }

    public String projectPath() {
        return projectPath;
    }

    /** Bus d'événements de l'espace de travail (un seul chemin de mise à jour). */
    public WorkspaceEvents events() {
        return events;
    }

    /** Dossiers ouverts (copie défensive : l'UI écrit via les mutateurs). */
    public Set<String> expandedDirs() {
        return expandedDirs;
    }

    /** Listings en cache (clé : dossier relatif). */
    public Map<String, FileTree.Listing> listingCache() {
        return listingCache;
    }

    /** Types affinés en cache (clé : chemin relatif). */
    public Map<String, FileKind> kindCache() {
        return kindCache;
    }

    /** Comptes d'éléments en cache (clé : dossier relatif). */
    public Map<String, FileTree.Count> countCache() {
        return countCache;
    }

    /** Chaînes compactes en cache (clé : premier dossier de la chaîne). */
    public Map<String, FileTree.Chain> chainCache() {
        return chainCache;
    }

    /** Vue Android active ? */
    public boolean androidView() {
        return androidView;
    }

    /** Bascule des vues Projet / Android. */
    public void setAndroidView(boolean android) {
        this.androidView = android;
    }

    /** Squelette de la vue Android (null = pas encore scanné). */
    public List<AndroidView.Node> androidNodes() {
        return androidNodes;
    }

    /** Pose le squelette de la vue Android (tâche de fond). */
    public void setAndroidNodes(List<AndroidView.Node> nodes) {
        this.androidNodes = nodes;
    }

    /** Oublie tout l'état d'arbre (actualisation complète demandée). */
    public void resetTreeState() {
        expandedDirs.clear();
        listingCache.clear();
        kindCache.clear();
        countCache.clear();
        chainCache.clear();
        androidNodes = null;
    }
}
