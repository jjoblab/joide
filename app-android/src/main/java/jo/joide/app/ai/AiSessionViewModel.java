package jo.joide.app.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jo.joide.ai.AgentLoop;
import jo.joide.ai.AgentWorkspace;
import jo.joide.ai.Conversation;
import jo.joide.ai.ConversationHistory;
import jo.joide.ai.ConversationStore;
import jo.joide.ai.ConversationTitles;
import jo.joide.ai.CancellationToken;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.ai.LlmTransport;
import jo.joide.ai.ProjectInstructions;
import jo.joide.ai.Usage;
import jo.joide.ai.provider.Efforts;
import jo.joide.ai.provider.ProviderRegistry;
import jo.joide.ai.provider.ProviderSpec;
import jo.joide.app.settings.SettingsIaPageFragment;

/**
 * Session IA (Lot 4) : possède la boucle, la conversation courante,
 * l'exécuteur borné et le jeton d'annulation. **Survit à la rotation, à la
 * fermeture du tiroir et au changement d'onglet** (portée : activité pour
 * l'assistant, atelier pour l'agent) ; un tour en cours continue en
 * arrière-plan de l'UI et se termine proprement.
 *
 * <p>Les événements du moteur arrivent sur le fil UI **groupés** (~16-33 ms)
 * dans {@link #events()} ; la persistance (double contenu : transcript vu +
 * historique exact du modèle) s'écrit après chaque tour, hors fil UI.</p>
 */
public class AiSessionViewModel extends ViewModel {

    private static final String TAG = "AiSession";
    private static final long BATCH_MS = 24;

    /** Rôle de la session : assistant général (accueil) ou agent d'un projet. */
    public enum Role { ASSISTANT, AGENT }

    private Role role = Role.ASSISTANT;
    private String projectPath;
    private Context applicationContext;
    private ConversationStore store;
    private AgentLoop loop;
    private Conversation conversation;
    private CancellationToken currentCancellation;
    private volatile boolean turnRunning;
    private String lastQuestion;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<List<AgentLoop.LoopEvent>> batch =
            new MutableLiveData<>(new ArrayList<>());
    private final List<AgentLoop.LoopEvent> pending = new ArrayList<>();
    private boolean batchScheduled;

    /** Clé de ViewModel pour un rôle/projet (une session par projet). */
    public static String key(Role role, @Nullable String projectPath) {
        return role == Role.ASSISTANT ? "session-ia-assistant"
                : "session-ia-agent-" + ConversationStore.fingerprint(projectPath);
    }

    /** Initialise la session (une fois) : rôle, projet, stockage. */
    public void init(Role role, @Nullable String projectPath, Context context) {
        if (this.store != null) {
            return;
        }
        this.role = role;
        this.projectPath = projectPath;
        this.applicationContext = context.getApplicationContext();
        String folder = role == Role.ASSISTANT ? "assistant"
                : "agent/" + ConversationStore.fingerprint(projectPath);
        this.store = new ConversationStore.JsonFiles(
                context.getFilesDir().toPath().resolve(folder),
                warning -> Log.w(TAG, "Conversation ignorée : " + warning));
    }

    public Role role() {
        return role;
    }

    /** Conversation courante (null : neuve, créée au premier message). */
    @Nullable
    public Conversation conversation() {
        return conversation;
    }

    /** Vrai si un tour est en cours (bouton Stop, garde d'envoi). */
    public boolean isTurnRunning() {
        return turnRunning;
    }

    /** Événements du moteur, groupés, sur le fil UI. */
    public LiveData<List<AgentLoop.LoopEvent>> events() {
        return batch;
    }

    /** Boucle courante (construite à la première demande depuis les réglages). */
    public AgentLoop loop(Context context) {
        if (loop == null) {
            loop = buildLoop(context);
        }
        return loop;
    }

    /** Reconstruit la boucle (changement de fournisseur/modèle en cours de
     *  conversation : la conversation est portée, l'historique rejoué). */
    public AgentLoop rebuildLoop(Context context) {
        AgentLoop fresh = buildLoop(context);
        if (conversation != null) {
            for (ConversationHistory.Entry entry : loop(context).history().full()) {
                fresh.history().add(entry);
            }
        }
        loop = fresh;
        return loop;
    }

    private AgentLoop buildLoop(Context context) {
        Context app = context.getApplicationContext();
        android.content.SharedPreferences prefs = app.getSharedPreferences(
                SettingsIaPageFragment.IA_PREFS, Context.MODE_PRIVATE);
        ProviderSpec spec = ProviderRegistry.byId(prefs.getString("fournisseur", null))
                .orElseGet(ProviderRegistry::defaultProvider);
        String baseUrl = spec.baseUrlEditable()
                ? prefs.getString("base_url_custom", null) : null;
        String apiEffort = Efforts.toApi(prefs.getString("effort", null));
        String reasoningEffort = spec.reasoningFormat()
                == ProviderSpec.ReasoningFormat.EFFORT_FIELD ? apiEffort : null;
        java.util.Map<String, Object> extras =
                new java.util.LinkedHashMap<>(spec.reasoningBody(apiEffort));
        String key = new jo.joide.app.ai.EncryptedKeyVault(app).apiKey(spec.id());
        // Attribution OpenRouter (X-Title : JoIDE) si activée (doc officielle).
        java.util.Map<String, String> headers = null;
        if ("openrouter".equals(spec.id())
                && prefs.getBoolean("or_attribution", true)) {
            headers = java.util.Collections.singletonMap("X-Title", "JoIDE");
        }
        // Routage OpenRouter documenté (Lot 7) : seuls les réglages posés
        // partent — sort, allow_fallbacks, require_parameters, data_collection.
        if ("openrouter".equals(spec.id())) {
            String sort = prefs.getString("or_sort", "");
            extras.putAll(jo.joide.ai.provider.OpenRouterOptions.body(
                    new jo.joide.ai.provider.OpenRouterOptions.Routing(
                            sort == null || sort.isEmpty() ? null : sort,
                            prefs.getBoolean("or_allow_fallbacks", true)
                                    ? null : Boolean.FALSE,
                            prefs.getBoolean("or_require_parameters", false)
                                    ? Boolean.TRUE : null,
                            prefs.getBoolean("or_data_collection", false)
                                    ? "deny" : null,
                            null)));
        }
        AgentWorkspace workspace = projectPath != null
                ? new AgentWorkspace.OnProject(Paths.get(projectPath))
                : new AgentWorkspace.InMemory();
        return new AgentLoop(new HttpLlmTransport(),
                spec.endpoint(baseUrl, key, headers), modelOf(prefs, spec.id()),
                workspace, jo.joide.app.settings.SettingsFragment.sharedGate(),
                null, prefs.getInt("iterations_max", 16), projectPath == null,
                prefs.getInt("tokens_max", 8192), reasoningEffort, extras,
                null, null);
    }

    private String modelOf(android.content.SharedPreferences prefs, String provider) {
        String model = prefs.getString("modele_" + provider, null);
        return model == null || model.trim().isEmpty() ? null : model;
    }

    // ---- Conversation : mémoire, persistance --------------------------------

    /** Nouvelle conversation (l'ancienne reste listée, rien n'est perdu). */
    public void newConversation() {
        conversation = null;
    }

    /** Charge une conversation : transcript rendu, historique rejoué au modèle. */
    public void loadConversation(String id) {
        executor.execute(() -> {
            Conversation loaded = null;
            try {
                loaded = store.load(id);
            } catch (IOException e) {
                Log.w(TAG, "Lecture de conversation impossible : " + e.getMessage());
            }
            final Conversation result = loaded;
            main.post(() -> {
                if (result == null) {
                    return;
                }
                conversation = result;
                // Boucle neuve, puis rejeu de l'historique exact archivé :
                // la conversation reprise garde son contexte au modèle.
                loop = null;
                AgentLoop fresh = loop(applicationContext);
                for (Conversation.HistoryEntry entry : result.history()) {
                    fresh.history().add(Conversation.toHistory(entry));
                }
            });
        });
    }

    /** Renomme la conversation courante (persisté). */
    public void renameConversation(String title) {
        if (conversation == null) {
            return;
        }
        long now = System.currentTimeMillis();
        conversation = conversation.withTitle(title, now);
        persist(conversation);
    }

    /** Supprime une conversation (la courante si c'est elle). */
    public void deleteConversation(String id) {
        executor.execute(() -> {
            try {
                store.delete(id);
            } catch (IOException e) {
                Log.w(TAG, "Suppression impossible : " + e.getMessage());
            }
        });
        if (conversation != null && conversation.id().equals(id)) {
            conversation = null;
        }
    }

    /** Démarre (ou reprend) une conversation sur le premier message. */
    void rememberUserMessage(String message, Context context) {
        long now = System.currentTimeMillis();
        if (conversation == null) {
            conversation = Conversation.fresh(ConversationStore.newId(), now)
                    .withMessage(new Conversation.Message("user", message), now)
                    .withTitle(ConversationTitles.deriveTitle(message), now)
                    .withModel(providerId(context), modelOf(prefs(context),
                            providerId(context)), now)
                    .withEffort(prefs(context).getString("effort", null), now);
        } else {
            conversation = conversation.withMessage(
                    new Conversation.Message("user", message), now);
        }
        persist(conversation);
    }

    /** Mémorise la réponse du modèle et l'historique exact du tour. */
    void rememberTurn(String answer, Usage usage) {
        if (conversation == null) {
            return;
        }
        long now = System.currentTimeMillis();
        conversation = conversation
                .withMessage(new Conversation.Message("assistant", answer), now)
                .withUsage(usage, now)
                .withHistory(snapshotHistory(), now);
        persist(conversation);
    }

    /** Historique exact du modèle, archivé à chaque tour. */
    private List<Conversation.HistoryEntry> snapshotHistory() {
        List<Conversation.HistoryEntry> snapshot = new ArrayList<>();
        AgentLoop current = loop;
        if (current != null) {
            for (ConversationHistory.Entry entry : current.history().full()) {
                snapshot.add(Conversation.fromHistory(entry));
            }
        }
        return snapshot;
    }

    private void persist(Conversation toSave) {
        executor.execute(() -> {
            try {
                store.save(toSave);
            } catch (IOException e) {
                Log.w(TAG, "Sauvegarde de conversation impossible : " + e.getMessage());
            }
        });
    }

    // ---- Tour -----------------------------------------------------------------

    /**
     * Lance un tour : capture du contexte faite par l'appelant (fil UI),
     * exécution sur l'exécuteur borné, événements groupés vers l'UI.
     */
    @MainThread
    public void sendTurn(String messageForModel, String rawMessage, boolean isNew,
            Context context) {
        if (turnRunning) {
            return;
        }
        turnRunning = true;
        lastQuestion = rawMessage;
        if (isNew) {
            rememberUserMessage(rawMessage, context);
        }
        currentCancellation = new CancellationToken();
        final String contextLine = preparedContext != null ? preparedContext
                : contextLineForLoop();
        final String projectRoot = projectPath;
        executor.execute(() -> {
            AgentLoop current = loop(applicationContext);
            current.setProjectContext(contextLine);
            if (projectRoot != null) {
                current.setProjectInstructions(
                        ProjectInstructions.cached(Paths.get(projectRoot)));
            }
            StringBuilder answer = new StringBuilder();
            final Usage[] usage = {Usage.unknown()};
            current.turn(messageForModel, this::emit, currentCancellation);
            // La réponse du tour vient du dernier assistant de l'historique.
            for (ConversationHistory.Entry entry : current.history().full()) {
                if ("assistant".equals(entry.role()) && entry.content() != null) {
                    answer.setLength(0);
                    answer.append(entry.content());
                }
            }
            // L'usage réel traverse le dernier TurnCompleted vu dans emit.
            rememberTurn(answer.toString(), lastUsage);
            main.post(() -> turnRunning = false);
        });
    }

    private volatile Usage lastUsage = Usage.unknown();

    /** Reçoit chaque événement du moteur (fil de la boucle) : regroupe pour l'UI. */
    private void emit(AgentLoop.LoopEvent event) {
        if (event instanceof AgentLoop.LoopEvent.TurnCompleted completed
                && completed.usage() != null) {
            lastUsage = completed.usage();
        }
        synchronized (pending) {
            pending.add(event);
        }
        main.post(() -> {
            if (batchScheduled) {
                return;
            }
            batchScheduled = true;
            main.postDelayed(this::flushBatch, BATCH_MS);
        });
    }

    private void flushBatch() {
        batchScheduled = false;
        List<AgentLoop.LoopEvent> toRender;
        synchronized (pending) {
            toRender = new ArrayList<>(pending);
            pending.clear();
        }
        batch.setValue(toRender);
    }

    /** Annule le tour en cours (bouton Stop). */
    public void cancelTurn() {
        if (currentCancellation != null) {
            currentCancellation.cancel();
        }
    }

    /**
     * Applique une proposition de code (bouton « Appliquer » d'un bloc) :
     * passe PAR LA PORTE de permission (diff réel dans l'invite), écrit via
     * l'espace de travail durci, enregistre le point de contrôle. Jamais en
     * contournant la porte — c'est la promesse du Lot 0, tenue au Lot 5.
     */
    public void applyProposal(String path, String content,
            java.util.function.Consumer<String> onSuccess,
            java.util.function.Consumer<String> onFailure) {
        if (projectPath == null) {
            onFailure.accept("L'application vit dans l'agent du projet (tiroir droit de l'atelier).");
            return;
        }
        executor.execute(() -> {
            try {
                AgentLoop current = loop(applicationContext);
                jo.joide.ai.PermissionGate.PermissionRequest request =
                        new jo.joide.ai.PermissionGate.PermissionRequest(
                                "appliquer_proposition", path,
                                "Écriture proposée par le modèle\nCible : " + path
                                        + " (" + content.length() + " caractères)");
                if (!jo.joide.app.settings.SettingsFragment.sharedGate()
                        .authorize(request)) {
                    onFailure.accept("Refusé (mode de permission) : rien n'a été écrit.");
                    return;
                }
                current.checkpoint().beforeChange(path);
                current.loopWorkspace().writeFile(path, content);
                current.checkpoint().afterChange(path, false);
                onSuccess.accept(path);
            } catch (Exception e) {
                onFailure.accept(e.getMessage() != null ? e.getMessage()
                        : String.valueOf(e));
            }
        });
    }

    /**
     * Suggestions de fichiers du projet pour la complétion « @ » (Lot 6) :
     * parcours borné (profondeur 4, 300 entrées, dossiers générés ignorés)
     * hors fil UI, résultats rendus sur le fil UI.
     */
    public void suggestMentionFiles(String prefix,
            java.util.function.Consumer<List<String>> onSuggestions) {
        if (projectPath == null) {
            onSuggestions.accept(java.util.Collections.emptyList());
            return;
        }
        executor.execute(() -> {
            List<String> matches = new ArrayList<>();
            try {
                java.nio.file.Path root = java.nio.file.Paths.get(projectPath);
                java.util.Deque<java.nio.file.Path> queue = new java.util.ArrayDeque<>();
                queue.add(root);
                int visited = 0;
                String needle = prefix == null ? "" : prefix.toLowerCase();
                while (!queue.isEmpty() && matches.size() < 8 && visited < 300) {
                    java.nio.file.Path dir = queue.poll();
                    try (var stream = java.nio.file.Files.list(dir)) {
                        for (java.nio.file.Path child : (Iterable<java.nio.file.Path>) stream::iterator) {
                            if (visited++ > 300) {
                                break;
                            }
                            String name = child.getFileName().toString();
                            if (name.equals(".git") || name.equals("build")
                                    || name.equals(".gradle") || name.startsWith(".")) {
                                continue;
                            }
                            String relative = root.relativize(child).toString();
                            if (needle.isEmpty()
                                    || relative.toLowerCase().contains(needle)) {
                                matches.add(relative);
                            }
                            if (matches.size() >= 8) {
                                break;
                            }
                            if (java.nio.file.Files.isDirectory(child)
                                    && root.relativize(child).getNameCount() < 4) {
                                queue.add(child);
                            }
                        }
                    } catch (Exception ignored) {
                        // Dossier illisible : on continue avec les autres.
                    }
                }
            } catch (Exception ignored) {
                // Projet illisible : aucune suggestion (honnête).
            }
            List<String> safe = new ArrayList<>(matches);
            main.post(() -> onSuggestions.accept(safe));
        });
    }

    /** Annule les effets du dernier tour (checkpoint) et le dit au modèle. */
    public String undoLastTurn() {
        AgentLoop current = loop;
        if (current == null || current.checkpoint() == null
                || !current.checkpoint().hasChanges()) {
            return null;
        }
        return current.checkpoint().undo();
    }

    /** Dernière question posée (régénération, édition). */
    @Nullable
    public String lastQuestion() {
        return lastQuestion;
    }

    /** Régénère : retire la dernière réponse du transcript, relance la question. */
    public void regenerate(Context context) {
        if (conversation == null || lastQuestion == null || turnRunning) {
            return;
        }
        List<Conversation.Message> messages = conversation.messages();
        if (!messages.isEmpty()
                && "assistant".equals(messages.get(messages.size() - 1).role())) {
            List<Conversation.Message> kept =
                    new ArrayList<>(messages.subList(0, messages.size() - 1));
            long now = System.currentTimeMillis();
            conversation = new Conversation(conversation.id(), conversation.title(),
                    conversation.createdAt(), now, conversation.providerId(),
                    conversation.model(), conversation.effort(), conversation.usage(),
                    kept, conversation.history());
        }
        sendTurn(lastQuestion, lastQuestion, false, context);
    }

    private String contextLineForLoop() {
        // Le contexte volatil réel (branche, fichier actif, problèmes) est
        // posé par le fragment (lecture des vues sur le fil UI) via
        // prepareContext avant sendTurn ; ici le repli honnête.
        return role == Role.ASSISTANT ? null : "Projet : " + projectPath;
    }

    /** Contexte volatil réel posé par l'UI (avant sendTurn) — fil UI. */
    public void prepareContext(String contextLine) {
        preparedContext = contextLine;
    }

    private volatile String preparedContext;

    private android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                SettingsIaPageFragment.IA_PREFS, Context.MODE_PRIVATE);
    }

    private String providerId(Context context) {
        String id = prefs(context).getString("fournisseur", null);
        return id != null && ProviderRegistry.isKnown(id)
                ? id : ProviderRegistry.defaultProvider().id();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdownNow();
    }
}
