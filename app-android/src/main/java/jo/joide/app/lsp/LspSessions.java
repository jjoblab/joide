package jo.joide.app.lsp;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jo.codeide.lsp.host.ConfigurationLsp;
import jo.codeide.lsp.host.EtatIndexation;
import jo.codeide.lsp.host.LecteurStatistiquesFichierDisque;
import jo.codeide.lsp.host.SessionLsp;
import jo.codeide.lsp.host.SessionLspKotlin;
import jo.codeide.lsp.host.SessionLspXml;
import jo.codeide.tooling.protocol.ClasspathResult;
import jo.joide.lsp.ForgeClasspath;
import jo.joide.lsp.LanguageClientBridge;
import kotlinx.coroutines.Dispatchers;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Les sessions LSP du projet ouvert (LOT-11, B-02) : les TROIS moteurs de la
 * brique code-lsp v1.15.0 — Java (le moteur ecj patché, l'index CIDX dont
 * l'état nourrit l'écran d'indexation), Kotlin et XML — montés EN PROCESSUS
 * à l'ouverture du projet, nourris du classpath Forge réel.
 *
 * <p>Le montage suit le chemin de production de la brique (USAGE §4.4) :
 * le moteur vit dans son environnement de compilation possédé par la
 * session Java, le routeur fichier→module y lit le modèle à chaque
 * requête ; les façades Kotlin et XML s'y chaînent (diffusion des
 * événements de fichiers externes). Le stockage d'index est EXPLICITE
 * (filesDir/lsp/index) : le défaut de la brique vit sous user.home, qui
 * n'existe pas sur appareil.</p>
 *
 * <p>La construction et la sync sont LOURDES (purge des temporaires,
 * lecture du modèle persisté, pass d'index) : {@link #open} et
 * {@link #syncForge} se calent sur le fil de l'appelant, jamais l'UI.</p>
 */
public final class LspSessions implements AutoCloseable {

    private static final String TAG = "joide-lsp";

    /** La session Java (moteur + index + modèle — la mère des trois). */
    private final SessionLsp sessionJava;

    /** La façade Kotlin (chaînée à la session Java). */
    private final SessionLspKotlin sessionKotlin;

    /** La façade XML (chaînée à la session Java). */
    private final SessionLspXml sessionXml;

    /** Le jar de la plateforme du projet (null : le moteur en vit sans). */
    private final Path androidJar;

    /** Le pont client : reçoit les diagnostics publiés par les moteurs. */
    private final LanguageClientBridge pont = new LanguageClientBridge();

    /** Le fil unique des serveurs montés (l'ordre LSP y est garanti). */
    private final ExecutorService filServeurs =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread fil = new Thread(runnable, "joide-lsp-serveurs");
                fil.setDaemon(true);
                return fil;
            });

    /** La racine du projet (identité des sessions). */
    private final Path racineProjet;

    /** Fermé une fois (les serveurs rendus ne survivent pas au close). */
    private final AtomicBoolean fermé = new AtomicBoolean();

    /** Le numéro de montage : une pose dépassée ne se branche pas. */
    private final AtomicInteger numérosPose = new AtomicInteger();

    /** Les serveurs initialisés par langage (un par langue et par projet). */
    private final java.util.Map<String, LanguageServer> serveursParLangage =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** La légende des jetons sémantiques par langage (lue à l'initialisation). */
    private final java.util.Map<String, List<String>> légendesParLangage =
            new java.util.concurrent.ConcurrentHashMap<>();

    private LspSessions(SessionLsp sessionJava, SessionLspKotlin sessionKotlin,
            SessionLspXml sessionXml, Path racineProjet, Path androidJar) {
        this.sessionJava = sessionJava;
        this.sessionKotlin = sessionKotlin;
        this.sessionXml = sessionXml;
        this.racineProjet = racineProjet;
        this.androidJar = androidJar;
    }

    /**
     * Monte les trois sessions du projet — LOURD, sur le fil de l'appelant
     * (arrière-plan).
     *
     * @param context     le contexte (stockage d'index sous filesDir).
     * @param projectRoot la racine du projet (forge.json).
     * @param androidJar  le jar de la plateforme (moteur Forge ou SDK),
     *                    null si absent — le contexte défaut en vit sans.
     * @return les sessions prêtes à recevoir le classpath ; null si le
     *         montage échoue (honnête : l'atelier vit sans LSP, il se dit).
     */
    public static LspSessions open(Context context, Path projectRoot,
            Path androidJar) {
        try {
            File stockage = new File(context.getFilesDir(), "lsp/index");
            SessionLsp java = new SessionLsp(
                    projectRoot.toFile(),
                    stockage,
                    Dispatchers.getIO(),
                    new LecteurStatistiquesFichierDisque(),
                    null,
                    androidJar != null ? androidJar.toFile() : null,
                    null,
                    new ConfigurationLsp());
            SessionLspKotlin kotlin = new SessionLspKotlin(
                    projectRoot.toFile(),
                    jo.codeide.lsp.kotlin.api.ServiceMoteurLangageKotlin
                            .Companion.getUnique(),
                    new LecteurStatistiquesFichierDisque(),
                    stockage,
                    java);
            SessionLspXml xml = new SessionLspXml(
                    projectRoot.toFile(),
                    jo.lspxml.api.ServiceMoteurLangageXml.INSTANCE.unique(),
                    new LecteurStatistiquesFichierDisque(),
                    java);
            return new LspSessions(java, kotlin, xml, projectRoot, androidJar);
        } catch (RuntimeException montage) {
            Log.w(TAG, "montage LSP impossible : " + montage.getMessage());
            return null;
        }
    }

    /**
     * Nourrit les trois moteurs du classpath Forge réel (traduit par
     * {@link ForgeClasspath}) : le modèle persisté d'abord (réouverture
     * quasi instantanée, comme la référence rouvre son index), la sync
     * fraîche ensuite — idempotent, un modèle identique est un no-op chaud.
     * C'EST cet appel qui déclenche le pass d'index (la phase
     * Bibliothèques &amp; SDK scanne android.jar et les jars résolus —
     * l'écran d'indexation de la référence).
     */
    public void syncForge() {
        ClasspathResult résultat = ForgeClasspath.of(racineProjet, androidJar);
        try {
            // La réouverture rapide : le modèle persisté nourrit la session
            // AVANT la sync fraîche (miroir du comportement CodeIDE).
            sessionJava.nourrirDepuisModelePersiste();
        } catch (RuntimeException persistant) {
            Log.w(TAG, "modèle persisté ignoré : " + persistant.getMessage());
        }
        try {
            sessionJava.appliquerClasspath(résultat);
            sessionKotlin.appliquerClasspath(résultat);
            sessionXml.appliquerClasspath(résultat, sessionJava.vueNomsXml());
        } catch (RuntimeException sync) {
            // La sync ne casse jamais l'atelier : les moteurs vivent avec le
            // modèle précédent, le journal le dit.
            Log.w(TAG, "sync classpath : " + sync.getMessage());
        }
    }

    /** L'état d'indexation affichable : la session Java (l'index maître,
     * miroir de l'écran d'indexation de la référence). */
    public EtatIndexation étatIndexation() {
        return sessionJava.getEtatIndexation().getValue();
    }

    /** Un pass d'index tourne-t-il (la puce s'affiche) ? */
    public boolean indexationEnCours() {
        return sessionJava.getEtatIndexation().getValue().getPhase()
                == jo.codeide.lsp.host.PhaseIndexation.CONSTRUCTION;
    }

    /** Relance le pass d'index (action « Réindexer » du dialogue). */
    public void réindexer() {
        try {
            sessionJava.reindexer();
        } catch (RuntimeException échec) {
            Log.w(TAG, "réindexation : " + échec.getMessage());
        }
    }

    /** Le pont client (diagnostics publiés par les moteurs). */
    public LanguageClientBridge pont() {
        return pont;
    }

    /** Le fil des serveurs (ordre LSP garanti — un seul fil, une file). */
    public ExecutorService filServeurs() {
        return filServeurs;
    }

    /** Le numéro d'une nouvelle pose de session d'édition. */
    public int nouvellePose() {
        return numérosPose.incrementAndGet();
    }

    /** La pose numéro N est-elle toujours la dernière ? */
    public boolean poseCourante(int numéro) {
        return numéro == numérosPose.get();
    }

    /** La légende des jetons sémantiques d'un langage (vide si inconnue). */
    public List<String> légendeJetons(String langage) {
        return légendesParLangage.getOrDefault(langage, List.of());
    }

    /**
     * Le serveur d'un langage, branché au pont client et initialisé — le
     * montage d'une session d'édition. Langages servis : « java »,
     * « kotlin », « xml » ; tout autre rend null (honnête : pas de moteur,
     * pas de session). Le serveur est MÉMORISÉ par langage : un seul par
     * projet, les documents s'y multiplient (le contrat LSP).
     *
     * @param langage l'identifiant de langage LSP du document.
     * @return le serveur initialisé, null si le langage n'est pas servi.
     */
    public LanguageServer serveurInitialisé(String langage) {
        LanguageServer connu = serveursParLangage.get(langage);
        if (connu != null) {
            return connu;
        }
        LanguageServer serveur;
        switch (langage) {
            case "java" -> serveur = sessionJava.serveur();
            case "kotlin" -> serveur = sessionKotlin.serveur();
            case "xml" -> serveur = sessionXml.serveur();
            default -> {
                return null;
            }
        }
        try {
            // Le client se pose par LA convention du serveur (connect, setClient
            // ou LanguageClientAware — chaque moteur de la brique choisit la sienne).
            jo.joide.lsp.LanguageClientBridge.brancher(serveur, pont);
            InitializeParams paramètres = new InitializeParams();
            paramètres.setProcessId(null);
            paramètres.setRootUri(racineProjet.toUri().toString());
            paramètres.setCapabilities(
                    new org.eclipse.lsp4j.ClientCapabilities());
            WorkspaceFolder dossier = new WorkspaceFolder();
            dossier.setUri(racineProjet.toUri().toString());
            dossier.setName(racineProjet.getFileName().toString());
            paramètres.setWorkspaceFolders(java.util.List.of(dossier));
            InitializeResult résultat = serveur.initialize(paramètres).get();
            serveur.initialized(new InitializedParams());
            List<String> légende = légendeDe(résultat);
            if (!légende.isEmpty()) {
                légendesParLangage.put(langage, légende);
            }
            serveursParLangage.put(langage, serveur);
            return serveur;
        } catch (Exception échec) {
            Log.w(TAG, "initialisation " + langage + " : " + échec);
            return null;
        }
    }

    /** La légende des jetons sémantiques de la réponse d'initialisation. */
    private static List<String> légendeDe(InitializeResult résultat) {
        try {
            org.eclipse.lsp4j.ServerCapabilities capacités =
                    résultat != null ? résultat.getCapabilities() : null;
            if (capacités == null
                    || capacités.getSemanticTokensProvider() == null) {
                return List.of();
            }
            org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions fournisseur =
                    capacités.getSemanticTokensProvider();
            org.eclipse.lsp4j.SemanticTokensLegend légende =
                    fournisseur.getLegend();
            return légende != null && légende.getTokenTypes() != null
                    ? List.copyOf(légende.getTokenTypes()) : List.of();
        } catch (RuntimeException illisible) {
            return List.of();
        }
    }

    /** Arrêt exhaustif : moteurs, coroutines, fil des serveurs. */
    @Override
    public void close() {
        if (!fermé.compareAndSet(false, true)) {
            return;
        }
        numérosPose.incrementAndGet(); // toute pose en vol est dépassée
        try {
            sessionXml.close();
        } catch (RuntimeException e) {
            Log.w(TAG, "fermeture xml : " + e.getMessage());
        }
        try {
            sessionKotlin.close();
        } catch (RuntimeException e) {
            Log.w(TAG, "fermeture kotlin : " + e.getMessage());
        }
        try {
            sessionJava.close();
        } catch (RuntimeException e) {
            Log.w(TAG, "fermeture java : " + e.getMessage());
        }
        filServeurs.shutdownNow();
    }
}
