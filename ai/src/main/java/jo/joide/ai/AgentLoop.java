package jo.joide.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import jo.joide.ai.tools.AgentTool;
import jo.joide.ai.tools.AgentTools;
import jo.joide.ai.tools.ToolArgs;

/**
 * Boucle d'agent (lots L7→3, conception §8) : requête → flux → outils →
 * réinjection → recommence, avec **plafond d'itérations** et **tour de
 * clôture sans outils** au plafond (bilan au lieu d'un échec en pure perte,
 * REF-119). Les **lectures** d'un même tour s'exécutent en parallèle avec
 * résultats **insérés dans l'ordre des appels** ; les **mutations
 * séquentiellement ensuite** (invites de permission jamais en course,
 * écritures ordonnées — REF-116). Tout outil mutant passe la **porte unique**
 * avec un **diff réel** ({@link DiffBuilder}) ; le refus renvoie au modèle un
 * résultat d'erreur informatif.
 *
 * <p>Lot 3 : les outils vivent dans {@code ai/tools/} (un fichier par outil,
 * schémas complets, arguments analysés par {@link ToolArgs} — fini les
 * indexOf qui coupaient au premier guillemet échappé) ; chaque tour a son
 * **point de contrôle** ({@link TurnCheckpoint} — annulation avec aveu des
 * suppressions irréversibles, REF-118) ; la **détection de boucle** arrête
 * un modèle qui répète le même appel ; les outils d'interaction
 * (questions à l'utilisateur, plan) suspendent ou rythment le tour.</p>
 */
public final class AgentLoop {

    /** Seuil de répétition d'un appel identique : le tour s'arrête en bilan. */
    private static final int LOOP_CALL_LIMIT = 3;

    /** Événement de boucle consommé par le tiroir IA (UI). */
    public sealed interface LoopEvent {

        /** Début d'un tour utilisateur. */
        record TurnStarted(String userMessage) implements LoopEvent {}

        /** Fragment de texte du modèle. */
        record Delta(String text) implements LoopEvent {}

        /** Fragment de raisonnement du modèle (jamais mélangé à la réponse). */
        record Reasoning(String text) implements LoopEvent {}

        /** Un outil démarre (nom + arguments). */
        record ToolStarted(String name, String argumentsJson) implements LoopEvent {}

        /** Résultat d'un outil (borné, contenu envoyé au modèle). */
        record ToolResult(String toolCallId, String summary, boolean isError)
                implements LoopEvent {}

        /** Demande d'approbation en attente (diff réel dans l'invite). */
        record PermissionPrompt(String tool, String path, String preview) implements LoopEvent {}

        /** Le modèle pose ses questions à l'utilisateur (la réponse suspend le tour). */
        record AwaitingUserInput(String summary) implements LoopEvent {}

        /** Le modèle publie son plan (étapes visibles dans l'UI). */
        record PlanUpdate(List<String> steps) implements LoopEvent {}

        /** Fin normale d'un tour (bilan, usage réel signalé par le fournisseur). */
        record TurnCompleted(int iterations, Usage usage) implements LoopEvent {}

        /** Échec du tour : message lisible, jamais la clé ; cause typée. */
        record TurnFailed(String message, LlmError error) implements LoopEvent {}
    }

    /** Fournisseur de réponses aux questions du modèle (l'hôte branché ou null). */
    public interface QuestionGate {

        /**
         * Pose les questions à l'utilisateur et rend sa réponse (texte) —
         * appelé sur le fil de la boucle, l'hôte déroute vers l'UI et attend.
         * Retour null = pas de réponse disponible.
         */
        String ask(List<Question> questions);

        /** Une question du modèle (texte + options suggérées). */
        record Question(String text, List<String> options) {}
    }

    private final LlmTransport transport;
    private final LlmTransport.Endpoint endpoint;
    private final String model;
    private final AgentWorkspace workspace;
    private final PermissionGate gate;
    private final ConversationHistory history = new ConversationHistory();
    private final int maxIterations;
    private final boolean toolsEnabled;
    private final int maxTokens;
    private final String reasoningEffort;
    private final Map<String, Object> extras;
    private final List<AgentTool> roster;
    private final QuestionGate questionGate;

    /** Instructions du projet (AGENTS.md borné) ; posées par l'hôte. */
    private volatile String projectInstructions;

    /** Contexte projet vivant (branche, fichier actif, problèmes) ; posé par l'hôte. */
    private volatile String projectContext = "";

    /** Point de contrôle du tour courant (annulation « Annuler ce tour »). */
    private TurnCheckpoint checkpoint;

    /** Consommateur d'événements du tour courant (les outils d'interaction y publient). */
    private volatile Consumer<LoopEvent> currentSink;

    public AgentLoop(LlmTransport transport, LlmTransport.Endpoint endpoint, String model,
            AgentWorkspace workspace, PermissionGate gate) {
        this(transport, endpoint, model, workspace, gate, null, 16, true, 4096,
                null, null, null, null);
    }

    /** Boucle avec plafond d'itérations explicite (roster par défaut). */
    public AgentLoop(LlmTransport transport, LlmTransport.Endpoint endpoint, String model,
            AgentWorkspace workspace, PermissionGate gate, int maxIterations) {
        this(transport, endpoint, model, workspace, gate, null, maxIterations, true,
                4096, null, null, null, null);
    }

    /**
     * Boucle complète (Lots 1-3) : roster injecté par l'hôte (les outils
     * d'interaction — questions, plan — sont ajoutés par la boucle elle-même),
     * plafond d'itérations, plafond de tokens, effort, extras fournisseur.
     */
    public AgentLoop(LlmTransport transport, LlmTransport.Endpoint endpoint, String model,
            AgentWorkspace workspace, PermissionGate gate, List<AgentTool> roster,
            int maxIterations, boolean toolsEnabled, int maxTokens,
            String reasoningEffort, Map<String, Object> extras, BuildRunner buildRunner,
            QuestionGate questionGate) {
        this.transport = transport;
        this.endpoint = endpoint;
        this.model = model;
        this.workspace = workspace;
        this.gate = gate;
        this.maxIterations = maxIterations;
        this.toolsEnabled = toolsEnabled;
        this.maxTokens = maxTokens;
        this.reasoningEffort = reasoningEffort;
        this.extras = extras == null ? java.util.Collections.emptyMap() : extras;
        this.questionGate = questionGate;
        // Sans outils (assistant général), le roster reste vide : l'assistant
        // ne peut ni lire ni modifier un projet, par construction.
        if (toolsEnabled) {
            List<AgentTool> composed = roster != null
                    ? new ArrayList<>(roster)
                    : new ArrayList<>(AgentTools.defaultRoster(null, buildRunner));
            composed.add(new AgentTools.AskUserTool(questionGate));
            composed.add(new AgentTools.PlanTool(steps -> {
                Consumer<LoopEvent> sink = currentSink;
                if (sink != null) {
                    sink.accept(new LoopEvent.PlanUpdate(steps));
                }
            }));
            this.roster = composed;
        } else {
            this.roster = java.util.Collections.emptyList();
        }
    }

    /** Historique complet (transcript persisté par l'hôte si besoin). */
    public ConversationHistory history() {
        return history;
    }

    /** Point de contrôle du dernier tour (annulation côté hôte). */
    public TurnCheckpoint checkpoint() {
        return checkpoint;
    }

    /** Espace de travail de la boucle (hôte : propositions applicables). */
    public AgentWorkspace loopWorkspace() {
        return workspace;
    }

    /** Instructions du projet (AGENTS.md borné) ; posées par l'hôte. */
    public void setProjectInstructions(String instructions) {
        this.projectInstructions = instructions;
    }

    /** Contexte projet vivant (valeurs réelles de l'hôte) ; rafraîchi chaque tour. */
    public void setProjectContext(String context) {
        this.projectContext = context == null ? "" : context;
    }

    /**
     * Exécute un tour utilisateur complet : boucle d'itérations (requête → flux
     * → outils → réinjection) jusqu'à une réponse sans appel d'outil, le
     * plafond (tour de clôture), une annulation ou un échec.
     */
    public void turn(String userMessage, Consumer<LoopEvent> sink) {
        turn(userMessage, sink, null);
    }

    /** Tour avec annulation (Lot 1) : Stop interrompt la requête et les outils. */
    public void turn(String userMessage, Consumer<LoopEvent> sink,
            CancellationToken cancellation) {
        currentSink = sink;
        history.add(new ConversationHistory.Entry("user", userMessage, null, null));
        sink.accept(new LoopEvent.TurnStarted(userMessage));
        checkpoint = new TurnCheckpoint(workspace);

        List<LlmTransport.ToolSpec> specs = new ArrayList<>();
        for (AgentTool tool : tools()) {
            specs.add(new LlmTransport.ToolSpec(tool.name(), tool.description(),
                    tool.schema()));
        }
        String grounding = SystemPrompt.grounding(specs, projectInstructions);

        Map<String, Integer> callCounts = new HashMap<>();
        int iteration = 0;
        while (iteration < maxIterations) {
            if (cancellation != null && cancellation.isCancelled()) {
                fail(sink, LlmError.of(LlmError.Kind.CANCELLED, null));
                return;
            }
            iteration++;
            List<LlmTransport.ChatMessage> messages = requestMessages(grounding);
            StringBuilder text = new StringBuilder();
            List<LlmTransport.ToolCall> calls = new ArrayList<>();
            Usage[] usage = {Usage.unknown()};
            LlmError[] failure = {null};
            try {
                transport.chat(endpoint,
                        new LlmTransport.ChatRequest(model, messages, specs,
                                maxTokens, reasoningEffort, cancellation, extras),
                        event -> {
                            if (event instanceof LlmTransport.StreamEvent.Text t) {
                                text.append(t.delta());
                                sink.accept(new LoopEvent.Delta(t.delta()));
                            } else if (event instanceof
                                    LlmTransport.StreamEvent.Reasoning r) {
                                sink.accept(new LoopEvent.Reasoning(r.delta()));
                            } else if (event instanceof
                                    LlmTransport.StreamEvent.ToolCallEvent c) {
                                calls.add(c.call());
                            } else if (event instanceof
                                    LlmTransport.StreamEvent.UsageEvent u) {
                                usage[0] = u.usage();
                            } else if (event instanceof LlmTransport.StreamEvent.Failure f
                                    && f.error() != null) {
                                failure[0] = f.error();
                            }
                        });
            } catch (LlmError error) {
                fail(sink, error);
                return;
            } catch (Exception e) {
                fail(sink, LlmError.of(LlmError.Kind.NETWORK,
                        e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
                return;
            }
            if (failure[0] != null) {
                fail(sink, failure[0]);
                return;
            }
            history.add(new ConversationHistory.Entry("assistant", text.toString(),
                    calls.isEmpty() ? null : calls, null));

            if (calls.isEmpty()) {
                sink.accept(new LoopEvent.TurnCompleted(iteration, usage[0]));
                return; // réponse finale du modèle
            }
            String loopStop = detectLoop(calls, callCounts);
            if (loopStop != null) {
                history.add(new ConversationHistory.Entry("system", loopStop, null, null));
                break; // le tour de clôture fait le bilan
            }
            executeCalls(calls, sink, cancellation);
            if (cancellation != null && cancellation.isCancelled()) {
                fail(sink, LlmError.of(LlmError.Kind.CANCELLED, null));
                return;
            }
        }
        // Plafond atteint ou boucle détectée : tour de clôture SANS outils.
        closeOut(grounding, sink, cancellation);
    }

    /**
     * Détection de boucle (Lot 3) : le même appel (nom + arguments) répété
     * {@value LOOP_CALL_LIMIT} fois arrête le tour — le bilan se fait sans
     * outils. Retourne la note système à injecter, ou null.
     */
    private static String detectLoop(List<LlmTransport.ToolCall> calls,
            Map<String, Integer> callCounts) {
        for (LlmTransport.ToolCall call : calls) {
            String key = call.name() + "|" + call.argumentsJson();
            int count = callCounts.merge(key, 1, Integer::sum);
            if (count >= LOOP_CALL_LIMIT) {
                return "L'appel « " + call.name() + " » avec les mêmes arguments a été "
                        + "répété " + count + " fois : ce tour est le dernier, fais le "
                        + "bilan de ce qui a été fait et de ce qui bloque.";
            }
        }
        return null;
    }

    /** Échec du tour : message rédigé + cause typée (jamais la clé). */
    private void fail(Consumer<LoopEvent> sink, LlmError error) {
        sink.accept(new LoopEvent.TurnFailed(error.getMessage(), error));
    }

    /** Tour de clôture : dernières instructions + réponse finale sans outils. */
    private void closeOut(String grounding, Consumer<LoopEvent> sink,
            CancellationToken cancellation) {
        List<LlmTransport.ChatMessage> messages = requestMessages(grounding);
        messages.add(LlmTransport.ChatMessage.system(SystemPrompt.WRAP_UP));
        StringBuilder text = new StringBuilder();
        try {
            transport.chat(endpoint,
                    new LlmTransport.ChatRequest(model, messages, java.util.List.of(),
                            Math.min(1024, maxTokens), null, cancellation, extras),
                    event -> {
                        if (event instanceof LlmTransport.StreamEvent.Text t) {
                            text.append(t.delta());
                            sink.accept(new LoopEvent.Delta(t.delta()));
                        }
                    });
        } catch (Exception e) {
            fail(sink, e instanceof LlmError error ? error
                    : LlmError.of(LlmError.Kind.NETWORK,
                            e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
            return;
        }
        history.add(new ConversationHistory.Entry("assistant", text.toString(), null, null));
        sink.accept(new LoopEvent.TurnCompleted(maxIterations, Usage.unknown()));
    }

    /** Messages de la requête : moitié stable en préfixe, historique compacté et apparié, volatile en fin. */
    private List<LlmTransport.ChatMessage> requestMessages(String grounding) {
        List<LlmTransport.ChatMessage> messages = new ArrayList<>();
        messages.add(LlmTransport.ChatMessage.system(grounding));
        for (ConversationHistory.Entry entry : ToolCallPairing.pair(history.compacted())) {
            messages.add(new LlmTransport.ChatMessage(entry.role(), entry.content(),
                    entry.toolCalls(), entry.toolCallId()));
        }
        // Sans outils (assistant général) : aucune porte à mentionner — le
        // contexte volatile dit simplement au modèle de répondre directement.
        messages.add(LlmTransport.ChatMessage.system(toolsEnabled
                ? SystemPrompt.sessionContext(gateMode(), projectContext)
                : SystemPrompt.sessionContext(projectContext)));
        return messages;
    }

    private PermissionGate.Mode gateMode() {
        return gate.mode();
    }

    /**
     * Exécute les appels d'un tour : lectures en parallèle, **résultats
     * insérés dans l'ordre des appels**, mutations séquentiellement ensuite
     * derrière la porte unique (avec diff réel et point de contrôle).
     * Une annulation interrompt : les appels non exécutés reçoivent un
     * résultat « interrompu » pour que la conversation reste envoyable.
     */
    private void executeCalls(List<LlmTransport.ToolCall> calls, Consumer<LoopEvent> sink,
            CancellationToken cancellation) {
        Map<String, AgentTool> rosterByName = new LinkedHashMap<>();
        for (AgentTool tool : tools()) {
            rosterByName.put(tool.name(), tool);
        }
        // Lectures d'abord, en parallèle, résultats dans l'ordre des appels.
        List<LlmTransport.ToolCall> reads = new ArrayList<>();
        for (LlmTransport.ToolCall call : calls) {
            AgentTool tool = rosterByName.get(call.name());
            if (tool != null && tool.readOnly()) {
                reads.add(call);
            }
        }
        if (!reads.isEmpty()) {
            List<java.util.concurrent.Callable<ConversationHistory.Entry>> tasks =
                    new ArrayList<>();
            for (LlmTransport.ToolCall call : reads) {
                tasks.add(() -> runTool(rosterByName.get(call.name()), call, sink, false));
            }
            try {
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(
                                Math.min(4, tasks.size()));
                try {
                    // invokeAll : l'ordre des futurs = l'ordre des tâches.
                    List<java.util.concurrent.Future<ConversationHistory.Entry>> futures =
                            pool.invokeAll(tasks);
                    for (java.util.concurrent.Future<ConversationHistory.Entry> future : futures) {
                        history.add(future.get());
                    }
                } finally {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                history.add(new ConversationHistory.Entry("tool",
                        "Erreur de l'outil : " + e.getCause(), null, null));
            }
        }
        // Mutations ensuite, séquentiellement et derrière la porte.
        for (LlmTransport.ToolCall call : calls) {
            AgentTool tool = rosterByName.get(call.name());
            if (tool == null || tool.readOnly()) {
                continue;
            }
            if (cancellation != null && cancellation.isCancelled()) {
                history.add(interrupted(call));
                continue;
            }
            history.add(runTool(tool, call, sink, true));
        }
    }

    /** Résultat « interrompu » (l'appariement appels/résultats reste valide). */
    private static ConversationHistory.Entry interrupted(LlmTransport.ToolCall call) {
        return new ConversationHistory.Entry("tool",
                SystemPrompt.INTERRUPTED_TOOL_RESULT, null, call.id());
    }

    /**
     * Exécute un appel : arguments typés (les erreurs d'argument partent au
     * modèle avec le motif), porte avec **diff réel** pour les mutations,
     * point de contrôle avant/après, résultat borné dans l'historique.
     */
    private ConversationHistory.Entry runTool(AgentTool tool, LlmTransport.ToolCall call,
            Consumer<LoopEvent> sink, boolean mutation) {
        sink.accept(new LoopEvent.ToolStarted(call.name(), call.argumentsJson()));
        ToolArgs args;
        try {
            args = ToolArgs.of(call.argumentsJson());
        } catch (ToolArgs.BadArguments bad) {
            sink.accept(new LoopEvent.ToolResult(call.id(), "arguments invalides", true));
            return toolError(call, bad.getMessage());
        }
        if (mutation) {
            PermissionGate.PermissionRequest request = new PermissionGate.PermissionRequest(
                    tool.name(), pathOf(args), diffPreview(tool, args));
            boolean allowed = gate.authorize(request);
            if (!allowed) {
                sink.accept(new LoopEvent.ToolResult(call.id(), "refusé", true));
                return new ConversationHistory.Entry("tool",
                        "Refusé par l'utilisateur (mode de permission) : la mutation « "
                                + tool.name() + " » n'a pas été exécutée. Continue en "
                                + "proposant autre chose ou en expliquant ce qu'il faut.",
                        null, call.id());
            }
        }
        String result;
        try {
            result = tool.run(workspace, args);
            recordCheckpoint(tool, args);
        } catch (ToolArgs.BadArguments bad) {
            sink.accept(new LoopEvent.ToolResult(call.id(), "arguments invalides", true));
            return toolError(call, bad.getMessage());
        } catch (Exception e) {
            sink.accept(new LoopEvent.ToolResult(call.id(), "erreur", true));
            return toolError(call, "Erreur de l'outil « " + tool.name() + " » : "
                    + (e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
        }
        sink.accept(new LoopEvent.ToolResult(call.id(),
                result.length() > 120 ? result.substring(0, 120) + "…" : result, false));
        return new ConversationHistory.Entry("tool", result, null, call.id());
    }

    /** Erreur rendue au modèle (argument invalide ou échec d'outil) : informative. */
    private static ConversationHistory.Entry toolError(LlmTransport.ToolCall call,
            String message) {
        return new ConversationHistory.Entry("tool", message, null, call.id());
    }

    /** Chemin visé par un appel (affichage de l'invite de permission). */
    private static String pathOf(ToolArgs args) {
        return args.stringOr("chemin", args.stringOr("requete", "sans chemin"));
    }

    /**
     * Aperçu **diff réel** avant/après (REF-117, Lot 3) : pour une écriture,
     * le contenu après vient des arguments ; pour un remplacement ciblé, la
     * simulation du remplacement ; pour renommage/suppression, la description
     * honnête du changement. Borné (la visionneuse complète vient au Lot 5).
     */
    private String diffPreview(AgentTool tool, ToolArgs args) {
        try {
            String path = args.stringOr("chemin", null);
            if (path == null) {
                return tool.name();
            }
            String before = workspace.fileBefore(path);
            String after = afterContentOf(tool, args, before);
            if (after == null) {
                // Renommage / suppression : pas de contenu comparable.
                return tool.name() + " : " + path;
            }
            if (after.equals(before)) {
                return tool.name() + " : " + path + " (aucun changement net)";
            }
            DiffBuilder.Diff diff = DiffBuilder.unified(before, after);
            if (diff == null) {
                return tool.name() + " : " + path + " (fichier trop grand pour un diff)";
            }
            String unified = diff.unified();
            return path + " " + diff.counters() + "\n"
                    + (unified.length() > 2_000
                            ? unified.substring(0, 2_000) + "\n… (diff tronqué dans l'invite)"
                            : unified);
        } catch (Exception e) {
            return tool.name() + " : aperçu indisponible";
        }
    }

    /** Contenu après la mutation, pour le diff d'approbation. */
    private static String afterContentOf(AgentTool tool, ToolArgs args, String before) {
        switch (tool.name()) {
            case "ecrire_fichier", "creer_fichier":
                return args.stringOr("contenu", "");
            case "modifier_fichier": {
                String oldText = args.stringOr("ancien_texte", null);
                String newText = args.stringOr("nouveau_texte", "");
                if (oldText == null || !before.contains(oldText)) {
                    return null; // l'outil échouera de toute façon : pas de diff trompeur
                }
                return before.replace(oldText, newText);
            }
            default:
                return null;
        }
    }

    /** Point de contrôle : retient l'état avant/après de chaque mutation. */
    private void recordCheckpoint(AgentTool tool, ToolArgs args) {
        if (checkpoint == null) {
            return;
        }
        String path = args.stringOr("chemin", null);
        if (path == null) {
            return;
        }
        checkpoint.beforeChange(path);
        checkpoint.afterChange(path, "supprimer_fichier".equals(tool.name()));
    }

    // ---- Roster ---------------------------------------------------------------

    /** Roster d'outils du tour (figé par instance de boucle ; vide sans outils). */
    List<AgentTool> tools() {
        return roster;
    }
}
