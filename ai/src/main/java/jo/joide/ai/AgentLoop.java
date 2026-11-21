package jo.joide.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boucle d'agent (lot L7, conception §8) : requête → flux → outils →
 * réinjection → recommence, avec **plafond d'itérations** et **tour de
 * clôture sans outils** au plafond (bilan au lieu d'un échec en pure perte,
 * REF-119). Les **lectures** d'un même tour s'exécutent en parallèle ; les
 * **mutations séquentiellement ensuite** (invites de permission jamais en
 * course, écritures ordonnées — REF-116). Tout outil mutant passe la **porte
 * unique** ; le refus renvoie au modèle un résultat d'erreur informatif.
 *
 * <p>Annulation par tour : l'enveloppe des points de contrôle annule
 * exactement créations et modifications, et **avoue honnêtement** ce qui est
 * irréversible (suppressions) au lieu de le prétendre annulable (REF-118) ;
 * une note est ensuite envoyée au modèle pour qu'il relise les fichiers.</p>
 */
public final class AgentLoop {

    /** Événement de boucle consommé par le tiroir IA (UI). */
    public sealed interface LoopEvent {

        /** Début d'un tour utilisateur. */
        record TurnStarted(String userMessage) implements LoopEvent {}

        /** Fragment de texte du modèle. */
        record Delta(String text) implements LoopEvent {}

        /** Un outil démarre (nom + arguments). */
        record ToolStarted(String name, String argumentsJson) implements LoopEvent {}

        /** Résultat d'un outil (borné, contenu envoyé au modèle). */
        record ToolResult(String toolCallId, String summary, boolean isError)
                implements LoopEvent {}

        /** Demande d'approbation en attente (diff dans l'invite). */
        record PermissionPrompt(String tool, String path, String preview) implements LoopEvent {}

        /** Fin normale d'un tour (bilan, usage). */
        record TurnCompleted(int iterations) implements LoopEvent {}

        /** Échec du tour (réseau, quota) : message lisible, jamais la clé. */
        record TurnFailed(String message) implements LoopEvent {}
    }

    /** Outil de l'agent (roster figé par tour, injecté par l'hôte). */
    public interface Tool {

        /** Nom exposé au modèle (unique). */
        String name();

        /** Description (moitié stable du prompt). */
        String description();

        /** Lecture seule ? (les lectures sont parallèles, les mutations séquentielles). */
        boolean readOnly();

        /**
         * Exécute l'outil sur l'espace de travail.
         *
         * @param workspace port étroit du projet.
         * @param argumentsJson arguments bruts du modèle (minimaux : chemin, requête…).
         * @return résultat borné à l'arrivée (contenu envoyé au modèle).
         */
        String run(AgentWorkspace workspace, String argumentsJson) throws Exception;
    }

    private final AiProvider provider;
    private final LlmTransport transport;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final AgentWorkspace workspace;
    private final PermissionGate gate;
    private final ConversationHistory history = new ConversationHistory();
    private final int maxIterations;
    private final BuildRunner buildRunner;

    /** Boucle standard : plafond d'itérations par tour (défaut 16), sans build. */
    public AgentLoop(AiProvider provider, LlmTransport transport, String baseUrl,
            String apiKey, String model, AgentWorkspace workspace, PermissionGate gate) {
        this(provider, transport, baseUrl, apiKey, model, workspace, gate, 16, null);
    }

    /** Boucle avec outil de build : l'agent construit via le BuildBackend du mode actif. */
    public AgentLoop(AiProvider provider, LlmTransport transport, String baseUrl,
            String apiKey, String model, AgentWorkspace workspace, PermissionGate gate,
            BuildRunner buildRunner) {
        this(provider, transport, baseUrl, apiKey, model, workspace, gate, 16, buildRunner);
    }

    public AgentLoop(AiProvider provider, LlmTransport transport, String baseUrl,
            String apiKey, String model, AgentWorkspace workspace, PermissionGate gate,
            int maxIterations) {
        this(provider, transport, baseUrl, apiKey, model, workspace, gate, maxIterations, null);
    }

    public AgentLoop(AiProvider provider, LlmTransport transport, String baseUrl,
            String apiKey, String model, AgentWorkspace workspace, PermissionGate gate,
            int maxIterations, BuildRunner buildRunner) {
        this.provider = provider;
        this.transport = transport;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.workspace = workspace;
        this.gate = gate;
        this.maxIterations = maxIterations;
        this.buildRunner = buildRunner;
    }

    /** Historique complet (transcript persisté par l'hôte si besoin). */
    public ConversationHistory history() {
        return history;
    }

    /**
     * Exécute un tour utilisateur complet : boucle d'itérations (requête → flux
     * → outils → réinjection) jusqu'à une réponse sans appel d'outil, le
     * plafond (tour de clôture) ou un échec.
     */
    public void turn(String userMessage, Consumer<LoopEvent> sink) {
        history.add(new ConversationHistory.Entry("user", userMessage, null, null));
        sink.accept(new LoopEvent.TurnStarted(userMessage));

        List<LlmTransport.ToolSpec> specs = new ArrayList<>();
        for (Tool tool : tools()) {
            specs.add(new LlmTransport.ToolSpec(tool.name(), tool.description()));
        }
        String grounding = SystemPrompt.grounding(specs, projectInstructions());

        int iteration = 0;
        while (iteration < maxIterations) {
            iteration++;
            List<LlmTransport.ChatMessage> messages = requestMessages(grounding);
            StringBuilder text = new StringBuilder();
            List<LlmTransport.ToolCall> calls = new ArrayList<>();
            try {
                transport.chat(baseUrl, apiKey,
                        new LlmTransport.ChatRequest(model, messages, specs, 4096),
                        event -> {
                            if (event instanceof LlmTransport.StreamEvent.Text t) {
                                text.append(t.delta());
                                sink.accept(new LoopEvent.Delta(t.delta()));
                            } else if (event instanceof
                                    LlmTransport.StreamEvent.ToolCallEvent c) {
                                calls.add(c.call());
                            }
                        });
            } catch (Exception e) {
                sink.accept(new LoopEvent.TurnFailed(e.getMessage() != null
                        ? e.getMessage() : String.valueOf(e)));
                return;
            }
            history.add(new ConversationHistory.Entry("assistant", text.toString(),
                    calls.isEmpty() ? null : calls, null));

            if (calls.isEmpty()) {
                sink.accept(new LoopEvent.TurnCompleted(iteration));
                return; // réponse finale du modèle
            }
            executeCalls(calls, sink);
        }
        // Plafond atteint : tour de clôture SANS outils (bilan, pas un échec muet).
        closeOut(grounding, sink);
    }

    /** Tour de clôture : dernières instructions + réponse finale sans outils. */
    private void closeOut(String grounding, Consumer<LoopEvent> sink) {
        List<LlmTransport.ChatMessage> messages = requestMessages(grounding);
        messages.add(LlmTransport.ChatMessage.system(SystemPrompt.WRAP_UP));
        StringBuilder text = new StringBuilder();
        try {
            transport.chat(baseUrl, apiKey,
                    new LlmTransport.ChatRequest(model, messages, List.of(), 1024),
                    event -> {
                        if (event instanceof LlmTransport.StreamEvent.Text t) {
                            text.append(t.delta());
                            sink.accept(new LoopEvent.Delta(t.delta()));
                        }
                    });
        } catch (Exception e) {
            sink.accept(new LoopEvent.TurnFailed(
                    "Arrêt au plafond d'itérations ; bilan impossible : "
                            + (e.getMessage() != null ? e.getMessage() : String.valueOf(e))));
            return;
        }
        history.add(new ConversationHistory.Entry("assistant", text.toString(), null, null));
        sink.accept(new LoopEvent.TurnCompleted(maxIterations));
    }

    /** Messages de la requête : moitié stable en préfixe, historique compacté et apparié, volatile en fin. */
    private List<LlmTransport.ChatMessage> requestMessages(String grounding) {
        List<LlmTransport.ChatMessage> messages = new ArrayList<>();
        messages.add(LlmTransport.ChatMessage.system(grounding));
        for (ConversationHistory.Entry entry : ToolCallPairing.pair(history.compacted())) {
            messages.add(new LlmTransport.ChatMessage(entry.role(), entry.content(),
                    entry.toolCalls(), entry.toolCallId()));
        }
        messages.add(LlmTransport.ChatMessage.system(
                SystemPrompt.sessionContext(gateMode(), projectContext())));
        return messages;
    }

    private PermissionGate.Mode gateMode() {
        return gate.mode();
    }

    /**
     * Exécute les appels d'un tour : lectures en parallèle, mutations
     * séquentielles ensuite (jamais en course — REF-116), chaque mutation
     * passe la porte unique avant d'agir.
     */
    private void executeCalls(List<LlmTransport.ToolCall> calls, Consumer<LoopEvent> sink) {
        Map<String, Tool> roster = new java.util.LinkedHashMap<>();
        for (Tool tool : tools()) {
            roster.put(tool.name(), tool);
        }
        // Lectures d'abord, en parallèle (résultats dans l'ordre des appels).
        List<LlmTransport.ToolCall> reads = calls.stream()
                .filter(c -> roster.get(c.name()) != null && roster.get(c.name()).readOnly())
                .toList();
        reads.parallelStream().forEach(call ->
                history.add(runTool(roster.get(call.name()), call, sink, false)));
        // Mutations ensuite, séquentiellement et derrière la porte.
        for (LlmTransport.ToolCall call : calls) {
            Tool tool = roster.get(call.name());
            if (tool == null || tool.readOnly()) {
                continue;
            }
            history.add(runTool(tool, call, sink, true));
        }
    }

    /** Exécute un appel : porte (mutations) → outil → résultat borné dans l'historique. */
    private ConversationHistory.Entry runTool(Tool tool, LlmTransport.ToolCall call,
            Consumer<LoopEvent> sink, boolean mutation) {
        sink.accept(new LoopEvent.ToolStarted(call.name(), call.argumentsJson()));
        if (mutation) {
            PermissionGate.PermissionRequest request = new PermissionGate.PermissionRequest(
                    tool.name(), firstPathOf(call.argumentsJson()),
                    previewFor(tool, call.argumentsJson()));
            boolean allowed = gate.authorize(request);
            sink.accept(new LoopEvent.ToolResult(call.id(), allowed ? "autorisé" : "refusé", !allowed));
            if (!allowed) {
                return new ConversationHistory.Entry("tool",
                        "Refusé par l'utilisateur (mode de permission) : la mutation « "
                                + tool.name() + " » n'a pas été exécutée. Continue en "
                                + "proposant autre chose ou en expliquant ce qu'il faut.",
                        null, call.id());
            }
        }
        String result;
        try {
            result = tool.run(workspace, call.argumentsJson());
        } catch (Exception e) {
            return new ConversationHistory.Entry("tool",
                    "Erreur de l'outil « " + tool.name() + " » : "
                            + (e.getMessage() != null ? e.getMessage() : String.valueOf(e)),
                    null, call.id());
        }
        sink.accept(new LoopEvent.ToolResult(call.id(),
                result.length() > 120 ? result.substring(0, 120) + "…" : result, false));
        return new ConversationHistory.Entry("tool", result, null, call.id());
    }

    /** Aperçu avant/après (diff dans l'invite d'approbation — REF-117). */
    private String previewFor(Tool tool, String argumentsJson) {
        String path = firstPathOf(argumentsJson);
        if (path == null) {
            return null;
        }
        try {
            String before = workspace.fileBefore(path);
            return before.isEmpty() ? "(fichier absent — création)"
                    : before.length() > 400 ? before.substring(0, 400) + "…" : before;
        } catch (Exception e) {
            return null;
        }
    }

    /** Chemin relatif dans les arguments JSON (analyse minimale : "path": "…"). */
    static String firstPathOf(String argumentsJson) {
        if (argumentsJson == null) {
            return null;
        }
        int at = argumentsJson.indexOf("\"");
        // "path":"valeur" ou {"path": "valeur"}
        int key = argumentsJson.indexOf("path");
        if (key < 0) {
            return null;
        }
        int start = argumentsJson.indexOf('"', key);
        if (start < 0) {
            return null;
        }
        int value = argumentsJson.indexOf('"', start + 1);
        // Le guillemet ouvrant de la valeur suit les deux-points.
        int colon = argumentsJson.indexOf(':', start);
        int open = argumentsJson.indexOf('"', colon);
        int close = argumentsJson.indexOf('"', open + 1);
        return (open >= 0 && close > open)
                ? argumentsJson.substring(open + 1, close) : null;
    }

    // ---- Outils du roster (lecture : jamais gated ; écriture : gated) -------

    /** Roster d'outils du tour (figé par instance de boucle). */
    List<Tool> tools() {
        java.util.List<Tool> roster = new java.util.ArrayList<>(java.util.List.of(
                readFileTool(), listFilesTool(), searchTool(),
                writeFileTool(), createFileTool(), deleteFileTool()));
        if (buildRunner != null) {
            roster.add(buildTool());
        }
        return java.util.List.copyOf(roster);
    }

    /** Outil de build : passe par le BuildRunner du mode actif (jamais un moteur concurrent). */
    private Tool buildTool() {
        return new Tool() {
            @Override public String name() { return "construire_module"; }
            @Override public String description() {
                return "Compile le module du projet via le moteur du mode actif "
                        + "(retourne le journal borné à la fin ; lecture seule pour la "
                        + "permission : le build écrit dans les répertoires de sortie).";
            }
            @Override public boolean readOnly() { return true; }
            @Override public String run(AgentWorkspace w, String args) throws Exception {
                BuildRunner.BuildOutcome outcome = buildRunner.build(
                        argumentsProjectRootOf(args));
                return (outcome.success() ? "BUILD REUSSI" : "BUILD ECHOUE")
                        + " (" + outcome.eventCount() + " evenements)\n"
                        + outcome.tailLog();
            }
        };
    }

    /** Racine projet des arguments ({"project":"…"} — défaut : le contexte posé par l'hôte). */
    private static String argumentsProjectRootOf(String args) {
        if (args == null) {
            return null;
        }
        int key = args.indexOf("project");
        if (key < 0) {
            return null;
        }
        int colon = args.indexOf(':', key);
        int open = args.indexOf('"', colon);
        int close = args.indexOf('"', open + 1);
        return (open >= 0 && close > open) ? args.substring(open + 1, close) : null;
    }

    private Tool readFileTool() {
        return new Tool() {
            @Override public String name() { return "lire_fichier"; }
            @Override public String description() {
                return "Lit un fichier du projet dans une fenêtre de lignes (lecture seule).";
            }
            @Override public boolean readOnly() { return true; }
            @Override public String run(AgentWorkspace w, String args) throws Exception {
                String path = firstPathOf(args);
                AgentWorkspace.Bounded bounded = w.readFile(path, 1, 200);
                return bounded.omitted() > 0
                        ? bounded.content() + "\n(" + bounded.omitted()
                                + " lignes non affichées — affinez la fenêtre)"
                        : bounded.content();
            }
        };
    }

    private Tool listFilesTool() {
        return new Tool() {
            @Override public String name() { return "lister_fichiers"; }
            @Override public String description() {
                return "Liste les fichiers d'un dossier du projet (borné).";
            }
            @Override public boolean readOnly() { return true; }
            @Override public String run(AgentWorkspace w, String args) throws Exception {
                String path = firstPathOf(args);
                AgentWorkspace.Bounded bounded = w.listFiles(path, 100);
                return bounded.omitted() > 0
                        ? bounded.content() + "\n(" + bounded.omitted() + " entrées manquantes)"
                        : bounded.content();
            }
        };
    }

    private Tool searchTool() {
        return new Tool() {
            @Override public String name() { return "chercher_texte"; }
            @Override public String description() {
                return "Cherche un texte dans les fichiers du projet (une occurrence par fichier).";
            }
            @Override public boolean readOnly() { return true; }
            @Override public String run(AgentWorkspace w, String args) throws Exception {
                String path = firstPathOf(args);
                AgentWorkspace.Bounded bounded = w.searchText(path, 30);
                return bounded.omitted() > 0
                        ? bounded.content() + "\n(" + bounded.omitted()
                                + " autres fichiers — affinez la requête)"
                        : bounded.content();
            }
        };
    }

    private Tool writeFileTool() {
        return new Tool() {
            @Override public String name() { return "ecrire_fichier"; }
            @Override public String description() {
                return "Écrit le contenu d'un fichier du projet (mutation — permission requise).";
            }
            @Override public boolean readOnly() { return false; }
            @Override public String run(AgentWorkspace w, String args) throws Exception {
                String path = firstPathOf(args);
                long written = w.writeFile(path, contentOf(args));
                return "Écrit : " + path + " (" + written + " caractères)";
            }
        };
    }

    private Tool createFileTool() {
        return new Tool() {
            @Override public String name() { return "creer_fichier"; }
            @Override public String description() {
                return "Crée un fichier nouveau (échoue s'il existe — mutation, permission requise).";
            }
            @Override public boolean readOnly() { return false; }
            @Override public String run(AgentWorkspace w, String args) throws Exception {
                String path = firstPathOf(args);
                return w.createFile(path, contentOf(args))
                        ? "Créé : " + path : "Échec : " + path + " existe déjà";
            }
        };
    }

    private Tool deleteFileTool() {
        return new Tool() {
            @Override public String name() { return "supprimer_fichier"; }
            @Override public String description() {
                return "Supprime un fichier du projet (IRRÉVERSIBLE — permission requise).";
            }
            @Override public boolean readOnly() { return false; }
            @Override public String run(AgentWorkspace w, String args) throws Exception {
                String path = firstPathOf(args);
                return w.deleteFile(path) ? "Supprimé : " + path
                        : "Introuvable : " + path;
            }
        };
    }

    /** Contenu "content" des arguments (analyse minimale). */
    private static String contentOf(String argumentsJson) {
        int content = argumentsJson.indexOf("\"content\"");
        if (content < 0) {
            return argumentsJson;
        }
        int colon = argumentsJson.indexOf(':', content);
        int open = argumentsJson.indexOf('"', colon);
        int close = argumentsJson.indexOf('"', open + 1);
        return argumentsJson.substring(open + 1, close);
    }

    // ---- Contexte projet (l'hôte fournit la réalité vivante) ---------------

    private String projectInstructions() {
        // Instructions projet (AGENTS.md) : lues par l'hôte, bornées, mises en
        // cache par (chemin, mtime) — REF-111. Fournies par le détenteur du projet.
        return projectInstructionsCache;
    }

    private String projectInstructionsCache;
    private String projectContextCache;

    /** Instructions AGENTS.md du projet (moitié stable — posées par l'hôte). */
    public void setProjectInstructions(String instructions) {
        this.projectInstructionsCache = instructions;
    }

    /** Contexte projet vivant (moitié volatile — rafraîchi par l'hôte chaque tour). */
    public void setProjectContext(String context) {
        this.projectContextCache = context;
    }

    private String projectContext() {
        return projectContextCache;
    }
}
