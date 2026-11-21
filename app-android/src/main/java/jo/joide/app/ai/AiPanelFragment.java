package jo.joide.app.ai;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import jo.joide.ai.AgentLoop;
import jo.joide.ai.AgentWorkspace;
import jo.joide.ai.AiProvider;
import jo.joide.ai.Conversation;
import jo.joide.ai.ConversationHistory;
import jo.joide.ai.ConversationStore;
import jo.joide.ai.ConversationTitles;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.ai.PermissionGate;
import jo.joide.app.R;
import jo.joide.app.databinding.AiPanelFragmentBinding;
import jo.joide.app.databinding.BulleReflexionBinding;
import jo.joide.app.databinding.BulleUserBinding;
import jo.joide.app.databinding.CarteErreurBinding;
import jo.joide.app.databinding.CarteOutilBinding;
import jo.joide.app.databinding.CarteQuestionBinding;
import jo.joide.app.databinding.ChipActionBinding;
import jo.joide.app.settings.SettingsIaPageFragment;

/**
 * Panneau IA (Lot 0 de la séparation Assistant / Agent) : **un seul rendu,
 * deux rôles par paramètres** (jamais deux classes copiées).
 *
 * <p><b>Assistant général</b> (accueil, écran plein) : conversation sans
 * projet et **sans aucun outil** ({@code AgentLoop} construit avec
 * {@code toolsEnabled = false} — le modèle ne reçoit aucun outil, il ne peut
 * donc ni lire ni modifier un projet, par construction). Bouton retour,
 * suggestions de départ sur conversation vide, conversations persistées
 * (un fichier JSON par conversation dans {@code filesDir/assistant/},
 * derrière {@link ConversationStore}), menu ⋯ avec « Créer un projet depuis
 * cette conversation ».</p>
 *
 * <p><b>Agent du projet</b> (tiroir droit de l'atelier) : travaille sur le
 * projet ouvert sous la porte de permission ; puces de contexte sous
 * l'en-tête (fichier ouvert, problèmes du dernier build, sélection) alimentées
 * par {@link AgentContext} — des valeurs **réelles** de l'atelier, jamais
 * inventées ; une puce sans valeur réelle reste masquée.</p>
 *
 * <p>La boucle d'agent est **unique pour la vie du fragment** : le modèle
 * garde la mémoire de la conversation entre les messages (plus une boucle
 * neuve par message). Les événements arrivent en flux (chaque delta rendu
 * dès sa réception, jamais en bloc après la fin) ; le fil de fond ne touche
 * jamais l'UI (handler principal, vues vérifiées) — plus aucun
 * {@code requireActivity()} hors fil UI.</p>
 */
public final class AiPanelFragment extends Fragment {

    private static final String TAG = "AiPanel";
    private static final String ARG_PROJECT = "project";
    private static final String ARG_CONVERSATION = "conversation";

    /** Bornage du contexte joint au message (fichier ouvert, sélection). */
    private static final int CONTEXT_MAX_CHARS = 6_000;

    /**
     * Agent du projet (tiroir droit de l'atelier) : lit, écrit et construit dans le
     * projet ouvert, sous la porte de permission.
     */
    public static AiPanelFragment forProject(String projectPath) {
        AiPanelFragment fragment = new AiPanelFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Assistant général (accueil) : nouvelle conversation sans projet —
     * aucun outil, rien à approuver.
     */
    public static AiPanelFragment assistant() {
        return new AiPanelFragment();
    }

    /**
     * Assistant général : reprise d'une conversation existante (transcript
     * rechargé, historique rejoué au modèle).
     */
    public static AiPanelFragment assistant(String conversationId) {
        AiPanelFragment fragment = new AiPanelFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CONVERSATION, conversationId);
        fragment.setArguments(args);
        return fragment;
    }

    /** Chemin du projet de l'agent ; null pour l'assistant général. */
    @Nullable
    private String projectRoot() {
        Bundle args = getArguments();
        return args != null ? args.getString(ARG_PROJECT, null) : null;
    }

    /** Identifiant de conversation reprise ; null (nouvelle) pour l'agent. */
    @Nullable
    private String conversationId() {
        Bundle args = getArguments();
        return args != null ? args.getString(ARG_CONVERSATION, null) : null;
    }

    private AiPanelFragmentBinding binding;
    private String lastQuestion;
    private AgentLoop loop;
    private ConversationStore store;
    private Conversation conversation;
    private int toolCount;

    /** Un seul tour à la fois (la boucle n'est pas concurrente) ; suivi côté fil de fond. */
    private volatile boolean turnRunning;

    /** Puces de contexte de l'agent : inclusion de chaque contexte au prochain message. */
    private boolean includeOpenFile;
    private boolean includeProblems;
    private boolean includeSelection;

    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean isAssistant() {
        return projectRoot() == null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = AiPanelFragmentBinding.inflate(inflater, container, false);
        // Deux rôles distincts : l'agent du projet (titre « Agent · nom », porte
        // de permission, puces de contexte) et l'assistant général de l'accueil
        // (écran plein : bouton retour, suggestions, conversations persistées).
        String root = projectRoot();
        if (root != null) {
            binding.chatTitle.setText(getString(R.string.chat_agent_title,
                    new File(root).getName()));
            binding.chatBackButton.setVisibility(View.GONE);
            renderContextChips();
        } else {
            binding.permPill.setVisibility(View.GONE);
            binding.chatBackButton.setVisibility(View.VISIBLE);
            binding.chatTitle.setText(getString(R.string.chat_assistant_title));
            store = new ConversationStore.JsonFiles(
                    requireContext().getFilesDir().toPath().resolve("assistant"),
                    warning -> Log.w(TAG, "Conversation ignorée : " + warning));
            loop = buildLoop();
            String conversationToLoad = conversationId();
            if (conversationToLoad != null) {
                loadConversation(conversationToLoad);
            }
            renderSuggestions();
        }

        // Retour (assistant : écran plein hébergé par l'activité).
        binding.chatBackButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        // Composer : envoyer la demande (bouton rond 48 primaire du studio).
        binding.sendButton.setOnClickListener(v ->
                ask(binding.inputField.getText().toString(), true));
        // Pièces jointes : honnête — le composer enrichi arrive avec la refonte.
        binding.attachButton.setOnClickListener(v ->
                Snackbar.make(binding.getRoot(), R.string.chat_attach_bientot,
                                Snackbar.LENGTH_SHORT).show());
        // Pills de la barre : le modèle ouvre la page Réglage IA, la permission
        // cycle les trois modes réels de la porte partagée (REF-117).
        binding.modelPill.setOnClickListener(v -> openSettingsIa());
        binding.permPill.setOnClickListener(v -> cyclePermissionMode());
        binding.moreButton.setOnClickListener(this::showMoreMenu);

        // Décideur de la porte (agent) : l'invite part sur le fil UI ; un
        // fragment parti (rotation, tiroir détruit) REFUSE (défaut sûr).
        jo.joide.ai.InteractivePermissionGate interactiveGate =
                jo.joide.app.settings.SettingsFragment.sharedGate();
        interactiveGate.setDecider((request, onAnswer) -> main.post(() -> {
            if (binding != null && isAdded()) {
                promptPermission(request, onAnswer);
            } else {
                onAnswer.accept(jo.joide.ai.InteractivePermissionGate.Decision.DENY);
            }
        }));

        // État honnête : sans clé au coffre pour le fournisseur actif (hors
        // passerelle locale), le panneau le dit au lieu d'échouer en silence.
        renderNoKeyIfAbsent();
        return binding.getRoot();
    }

    // ---- Réglages réellement lus (fournisseur, modèle, clé, itérations) ------

    /** Fournisseur actif persisté (page Réglage IA). */
    private String providerId() {
        return requireContext()
                .getSharedPreferences(SettingsIaPageFragment.IA_PREFS,
                        android.content.Context.MODE_PRIVATE)
                .getString("fournisseur", "laboratoire");
    }

    /** Modèle persisté POUR CE fournisseur (repli : premier modèle statique). */
    private String persistedModel(String provider, AiProvider ai) {
        String model = requireContext()
                .getSharedPreferences(SettingsIaPageFragment.IA_PREFS,
                        android.content.Context.MODE_PRIVATE)
                .getString("modele_" + provider, null);
        return model != null ? model
                : (ai.staticModels().isEmpty() ? provider : ai.staticModels().get(0));
    }

    /** Clé du fournisseur actif au coffre chiffré. */
    private String vaultKey(String provider) {
        return new EncryptedKeyVault(requireContext()).apiKey(provider);
    }

    /**
     * Boucle du rôle courant : l'assistant n'expose **aucun outil**
     * ({@code toolsEnabled = false}) ; l'agent garde le roster complet derrière
     * la porte. Plafond d'itérations lu des réglages.
     */
    private AgentLoop buildLoop() {
        HttpLlmTransport transport = new HttpLlmTransport();
        String provider = providerId();
        AiProvider ai = "passerelle".equals(provider)
                ? AiProvider.gateway(transport)
                : AiProvider.laboratory(transport);
        String root = projectRoot();
        AgentWorkspace workspace = root != null
                ? new AgentWorkspace.OnProject(Paths.get(root))
                : new AgentWorkspace.InMemory();
        int maxIterations = requireContext()
                .getSharedPreferences(SettingsIaPageFragment.IA_PREFS,
                        android.content.Context.MODE_PRIVATE)
                .getInt("iterations_max", 16);
        return new AgentLoop(ai, transport, ai.defaultBaseUrl(), vaultKey(provider),
                persistedModel(provider, ai), workspace,
                jo.joide.app.settings.SettingsFragment.sharedGate(),
                maxIterations, null, root == null);
    }

    /** Ouvre la page Réglage IA (fournisseurs, clé, modèle) — plein écran. */
    private void openSettingsIa() {
        if (isAssistant()) {
            getParentFragmentManager().beginTransaction()
                    .replace(((ViewGroup) requireView().getParent()).getId(),
                            new SettingsIaPageFragment())
                    .addToBackStack(null)
                    .commit();
        } else {
            // Agent (fragment enfant du tiroir) : la page s'ouvre par-dessus
            // l'atelier sur le conteneur de l'activité, pas dans le tiroir.
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, new SettingsIaPageFragment())
                    .addToBackStack(null)
                    .commit();
        }
    }

    /** Menu ⋯ : Réglages IA pour tous ; création de projet (assistant avec un message). */
    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu =
                new android.widget.PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.ai_more_menu);
        boolean canCreateProject = isAssistant() && conversation != null
                && conversation.firstUserMessage() != null;
        menu.getMenu().findItem(R.id.action_create_project).setVisible(canCreateProject);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_create_project) {
                createProjectFromConversation();
                return true;
            }
            if (item.getItemId() == R.id.action_ia_settings) {
                openSettingsIa();
                return true;
            }
            return false;
        });
        menu.show();
    }

    /** « Créer un projet depuis cette conversation » : nom dérivé du premier message. */
    private void createProjectFromConversation() {
        String firstMessage = conversation != null
                && conversation.firstUserMessage() != null
                ? conversation.firstUserMessage().content() : null;
        String suggested = firstMessage != null
                ? ConversationTitles.suggestProjectName(firstMessage) : "";
        jo.joide.app.CreateProjectFragment target = suggested.isEmpty()
                ? new jo.joide.app.CreateProjectFragment()
                : jo.joide.app.CreateProjectFragment.withSuggestedName(suggested);
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(), target)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderPermissionPill();
        renderModelPill();
        if (!isAssistant()) {
            renderContextChips();
        }
    }

    /** La pill permission affiche le mode réel de la porte partagée. */
    private void renderPermissionPill() {
        jo.joide.ai.InteractivePermissionGate shared =
                jo.joide.app.settings.SettingsFragment.sharedGate();
        PermissionGate.Mode mode = shared.mode();
        binding.permPill.setText(mode == PermissionGate.Mode.AUTO_ACCEPT
                ? getString(R.string.chat_perm_auto)
                : mode == PermissionGate.Mode.PLAN_ONLY
                ? getString(R.string.chat_perm_plan)
                : getString(R.string.chat_perm_ask));
    }

    /** Cycle la permission (demander → plan → auto) sur la porte partagée. */
    private void cyclePermissionMode() {
        jo.joide.ai.InteractivePermissionGate shared =
                jo.joide.app.settings.SettingsFragment.sharedGate();
        PermissionGate.Mode next = switch (shared.mode()) {
            case ASK_EACH -> PermissionGate.Mode.PLAN_ONLY;
            case PLAN_ONLY -> PermissionGate.Mode.AUTO_ACCEPT;
            case AUTO_ACCEPT -> PermissionGate.Mode.ASK_EACH;
        };
        shared.setMode(next);
        renderPermissionPill();
    }

    /** Pill modèle : le modèle réellement persisté pour le fournisseur actif. */
    private void renderModelPill() {
        HttpLlmTransport transport = new HttpLlmTransport();
        String provider = providerId();
        AiProvider ai = "passerelle".equals(provider)
                ? AiProvider.gateway(transport)
                : AiProvider.laboratory(transport);
        binding.modelPill.setText(getString(R.string.chat_model_pill,
                persistedModel(provider, ai)));
    }

    // ---- Demande : un tour de la boucle d'agent ------------------------------

    /**
     * Demande de l'utilisateur : un tour de la boucle (unique pour le
     * fragment : le modèle garde la mémoire de la conversation).
     *
     * @param isNew faux pour une relance (Réessayer) : ni bulle ni persistance
     *              en double — la question est déjà au transcript.
     */
    private void ask(String message, boolean isNew) {
        if (message == null || message.trim().isEmpty() || turnRunning) {
            return;
        }
        turnRunning = true;
        lastQuestion = message;
        toolCount = 0;
        if (isNew) {
            binding.inputField.setText("");
            appendUserBubble(message);
            if (isAssistant()) {
                rememberUserMessage(message);
                binding.chipsRow.setVisibility(View.GONE);
            }
        }

        // Carte de réflexion (studio : sc_high rayon 14, « En réflexion… »).
        startThinking();

        // Agent : capture des contextes actifs (fil UI — l'éditeur et le dock
        // se lisent ici), puis remise à OFF des puces (prochain message only).
        String contextBlock = null;
        if (!isAssistant()) {
            contextBlock = captureContextBlock();
            resetContextChips();
        }
        String messageForModel = contextBlock == null
                ? message : contextBlock + "\n" + message;

        if (loop == null) {
            loop = buildLoop();
        }
        loop.setProjectContext(isAssistant()
                ? getString(R.string.chat_assistant_contexte)
                : "Projet : " + projectRoot());
        binding.sendButton.setEnabled(false);

        // La boucle tourne hors fil UI ; chaque événement est rendu dès son
        // arrivée (flux visible), la réponse est accumulée côté fil de fond
        // (déterministe) puis persistée pour l'assistant. Le drapeau de tour
        // vit ici : même si la vue est détruite en cours de tour (rotation,
        // tiroir recréé), le suivant redevient possible à la fin réelle.
        new Thread(() -> {
            StringBuilder answer = new StringBuilder();
            try {
                loop.turn(messageForModel, event -> {
                    if (event instanceof AgentLoop.LoopEvent.Delta delta) {
                        answer.append(delta.text());
                    }
                    main.post(() -> {
                        if (binding != null) {
                            render(event);
                        }
                    });
                });
            } finally {
                turnRunning = false;
                main.post(() -> {
                    if (binding != null) {
                        binding.sendButton.setEnabled(true);
                    }
                });
            }
            if (isAssistant() && answer.length() > 0) {
                persistAssistantAnswer(answer.toString());
            }
        }, "agent-ia").start();
    }

    /** Rendu d'un événement de boucle : états du studio Chat IA. */
    private void render(AgentLoop.LoopEvent event) {
        if (event instanceof AgentLoop.LoopEvent.Delta delta) {
            // Le texte du modèle alimente la carte de réflexion (mono onCreate
            // et autres fragments de code restent lisibles dans le flux).
            appendThinkingDelta(delta.text());
        } else if (event instanceof AgentLoop.LoopEvent.ToolStarted started) {
            // Carte d'outil en cours : spinner + « nom · chemin ».
            endThinking();
            appendToolCard(started.name(), true, false);
        } else if (event instanceof AgentLoop.LoopEvent.ToolResult result) {
            toolCount++;
            appendToolCard(result.summary(), false, result.isError());
        } else if (event instanceof AgentLoop.LoopEvent.TurnCompleted completed) {
            // La carte de réflexion devient la carte de réponse : le texte du
            // modèle RESTE au transcript (il n'est plus supprimé en fin de tour).
            keepThinkingAsAnswer();
            if (toolCount > 0) {
                appendToolCard(getString(R.string.chat_tool_actions, toolCount),
                        false, false);
            }
        } else if (event instanceof AgentLoop.LoopEvent.TurnFailed failure) {
            keepThinkingAsAnswer();
            String reason = failure.message() != null
                    ? failure.message() : getString(R.string.chat_error_generic);
            renderError(reason, failure.message());
        }
    }

    // ---- Conversations de l'assistant (persistance, rechargement) ------------

    /** Mémorise le message utilisateur : conversation neuve ou existante, puis sauvegarde. */
    private void rememberUserMessage(String message) {
        long now = System.currentTimeMillis();
        if (conversation == null) {
            conversation = Conversation.fresh(ConversationStore.newId(), now)
                    .withMessage(new Conversation.Message("user", message), now)
                    .withTitle(ConversationTitles.deriveTitle(message), now);
        } else {
            conversation = conversation.withMessage(
                    new Conversation.Message("user", message), now);
        }
        final Conversation toSave = conversation;
        new Thread(() -> saveQuietly(toSave), "assistant-sauvegarde").start();
    }

    /** Mémorise la réponse du modèle (appelé du fil de fond, à la fin du tour). */
    private void persistAssistantAnswer(String answer) {
        conversation = conversation.withMessage(
                new Conversation.Message("assistant", answer), System.currentTimeMillis());
        saveQuietly(conversation);
    }

    private void saveQuietly(Conversation toSave) {
        try {
            store.save(toSave);
        } catch (IOException e) {
            Log.w(TAG, "Sauvegarde de conversation impossible : "
                    + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    /** Recharge une conversation : transcript rendu, historique rejoué au modèle. */
    private void loadConversation(String id) {
        new Thread(() -> {
            Conversation loaded = null;
            try {
                loaded = store.load(id);
            } catch (IOException e) {
                Log.w(TAG, "Lecture de conversation impossible : "
                        + (e.getMessage() != null ? e.getMessage() : e));
            }
            final Conversation result = loaded;
            main.post(() -> {
                if (binding == null || result == null) {
                    return;
                }
                conversation = result;
                rebuildTranscript(result);
                replayHistory(result);
                renderSuggestions();
                renderNoKeyIfAbsent();
            });
        }, "assistant-lecture").start();
    }

    /** Reconstruit le transcript visuel depuis la conversation persistée. */
    private void rebuildTranscript(Conversation loaded) {
        binding.transcriptList.removeAllViews();
        thinkingBinding = null;
        for (Conversation.Message message : loaded.messages()) {
            if ("user".equals(message.role())) {
                appendUserBubble(message.content());
            } else if ("assistant".equals(message.role())) {
                appendAnswerCard(message.content());
            }
        }
        scrollDown();
    }

    /** Rejoue l'historique au modèle : la conversation reprise garde son contexte. */
    private void replayHistory(Conversation loaded) {
        for (Conversation.Message message : loaded.messages()) {
            loop.history().add(new ConversationHistory.Entry(
                    message.role(), message.content(), null, null));
        }
    }

    /** État honnête : sans clé (fournisseur distant actif), le panneau le dit. */
    private void renderNoKeyIfAbsent() {
        String provider = providerId();
        if ("passerelle".equals(provider)) {
            return; // passerelle locale : fonctionne sans clé
        }
        if (vaultKey(provider) == null) {
            renderError(getString(R.string.chat_no_key), null);
        }
    }

    // ---- Suggestions de départ (assistant, conversation vide) ----------------

    /** Suggestions sur conversation vide : toucher remplit le champ, n'envoie pas. */
    private void renderSuggestions() {
        if (!isAssistant()) {
            return;
        }
        boolean empty = conversation == null || conversation.messages().isEmpty();
        binding.chipsRow.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty || binding.chipsRow.getChildCount() > 0) {
            return;
        }
        int[] labels = {R.string.chat_suggestion_concept, R.string.chat_suggestion_project,
                R.string.chat_suggestion_build, R.string.chat_suggestion_java};
        for (int label : labels) {
            String suggestion = getString(label);
            TextView chip = new TextView(requireContext());
            chip.setText(suggestion);
            chip.setTextColor(getResources().getColor(R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setPadding(dip(16), 0, dip(16), 0);
            chip.setMinHeight(dip(36));
            chip.setBackground(getResources().getDrawable(R.drawable.fond_chip_action));
            chip.setOnClickListener(v -> binding.inputField.setText(suggestion));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(36));
            params.setMarginEnd(dip(8));
            binding.chipsRow.addView(chip, params);
        }
    }

    // ---- Puces de contexte de l'agent (valeurs réelles de l'atelier) ---------

    /** Contexte vivant fourni par l'hôte (WorkbenchFragment) ; null sinon. */
    @Nullable
    private AgentContext agentContext() {
        return getParentFragment() instanceof AgentContext context ? context : null;
    }

    /** Puces de contexte : une par valeur réelle présente ; masquées sans valeur. */
    private void renderContextChips() {
        if (isAssistant()) {
            return;
        }
        binding.chipsRow.removeAllViews();
        AgentContext context = agentContext();
        if (context == null) {
            binding.chipsRow.setVisibility(View.GONE);
            return;
        }
        String fileName = context.agentActiveFileName();
        List<String> problems = context.agentProblemLines();
        String selection = context.agentSelectionText();
        boolean any = false;
        if (fileName != null) {
            binding.chipsRow.addView(contextChip(
                    getString(R.string.agent_chip_open_file, fileName),
                    () -> includeOpenFile = !includeOpenFile, () -> includeOpenFile));
            any = true;
        }
        if (problems != null && !problems.isEmpty()) {
            binding.chipsRow.addView(contextChip(
                    getString(R.string.agent_chip_problems, problems.size()),
                    () -> includeProblems = !includeProblems, () -> includeProblems));
            any = true;
        }
        if (selection != null) {
            binding.chipsRow.addView(contextChip(getString(R.string.agent_chip_selection),
                    () -> includeSelection = !includeSelection, () -> includeSelection));
            any = true;
        }
        binding.chipsRow.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** Une puce de contexte : bascule l'inclusion de son contexte au prochain message. */
    private TextView contextChip(String label, Runnable toggle,
            java.util.function.BooleanSupplier state) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setTextSize(13f);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setPadding(dip(15), 0, dip(15), 0);
        chip.setMinHeight(dip(32));
        chip.setContentDescription(getString(R.string.agent_chip_cd_toggle));
        chip.setOnClickListener(v -> {
            toggle.run();
            styleContextChip(chip, state);
        });
        styleContextChip(chip, state);
        return chip;
    }

    /** Style on/off d'une puce de contexte (ON = sc2/sur_sc2, comme le studio). */
    private void styleContextChip(TextView chip, java.util.function.BooleanSupplier state) {
        boolean on = state.getAsBoolean();
        chip.setBackground(getResources().getDrawable(
                on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        chip.setTextColor(getResources().getColor(on ? R.color.sur_sc2 : R.color.sur));
    }

    /** Remet toutes les puces à OFF après l'envoi (contexte du prochain message). */
    private void resetContextChips() {
        includeOpenFile = false;
        includeProblems = false;
        includeSelection = false;
        renderContextChips();
    }

    /**
     * Bloc de contexte joint au prochain message (fil UI) : fichier ouvert
     * (tampon vivant, borné), problèmes réels du dernier build, sélection.
     * Rendu « Contexte joint … » compréhensible par le modèle.
     */
    @Nullable
    private String captureContextBlock() {
        AgentContext context = agentContext();
        if (context == null) {
            return null;
        }
        StringBuilder block = new StringBuilder("Contexte joint à la demande :\n");
        boolean any = false;
        if (includeOpenFile) {
            String path = context.agentActiveFilePath();
            if (path != null) {
                block.append("Fichier ouvert : ").append(path).append('\n');
                String content = context.agentActiveFileContent();
                if (content != null) {
                    block.append(bound(content)).append('\n');
                }
                any = true;
            }
        }
        if (includeProblems) {
            List<String> problems = context.agentProblemLines();
            if (problems != null && !problems.isEmpty()) {
                block.append("Problèmes du dernier build :\n");
                for (String problem : problems) {
                    block.append("- ").append(problem).append('\n');
                }
                any = true;
            }
        }
        if (includeSelection) {
            String selection = context.agentSelectionText();
            if (selection != null) {
                block.append("Sélection dans le fichier ouvert :\n")
                        .append(bound(selection)).append('\n');
                any = true;
            }
        }
        return any ? block.toString() : null;
    }

    /** Bornage honnête d'un contenu joint (garde-fou de contexte). */
    private static String bound(String content) {
        if (content.length() <= CONTEXT_MAX_CHARS) {
            return content;
        }
        return content.substring(0, CONTEXT_MAX_CHARS)
                + "\n… (contenu tronqué : " + (content.length() - CONTEXT_MAX_CHARS)
                + " caractères restants)";
    }

    // ---- Événements de boucle : fabrique de vues --------------------------------

    /** Carte de réflexion (studio : fond sc_high rayon 14, barre 7×11 primaire). */
    private void startThinking() {
        endThinking();
        thinkingBinding = BulleReflexionBinding.inflate(getLayoutInflater());
        thinkingBinding.thinkingLabel.setText(R.string.chat_thinking);
        thinkingBinding.thinkingText.setText("");
        appendView(thinkingBinding.getRoot());
    }

    private BulleReflexionBinding thinkingBinding;

    /** Un delta de texte allonge la carte de réflexion (le flux du modèle). */
    private void appendThinkingDelta(String text) {
        if (thinkingBinding == null) {
            startThinking();
        }
        thinkingBinding.thinkingText.append(text);
        scrollDown();
    }

    /** Ferme la carte de réflexion (remplacée par une carte d'outil). */
    private void endThinking() {
        if (thinkingBinding != null) {
            binding.transcriptList.removeView(thinkingBinding.getRoot());
            thinkingBinding = null;
        }
    }

    /** La carte de réflexion devient la carte de réponse : elle RESTE au transcript. */
    private void keepThinkingAsAnswer() {
        if (thinkingBinding != null) {
            thinkingBinding.thinkingLabel.setText(R.string.chat_answer_label);
            thinkingBinding = null;
        }
    }

    /** Carte de réponse d'une conversation reprise (texte persisté). */
    private void appendAnswerCard(String text) {
        BulleReflexionBinding card = BulleReflexionBinding.inflate(getLayoutInflater());
        card.thinkingLabel.setText(R.string.chat_answer_label);
        card.thinkingText.setText(text);
        appendView(card.getRoot());
    }

    /** Bulle utilisateur (studio : sc2 à droite, rayons 20/20/6/20). */
    private void appendUserBubble(String message) {
        BulleUserBinding user = BulleUserBinding.inflate(getLayoutInflater());
        user.userBubble.setText(message);
        appendView(user.getRoot());
    }

    /** Carte d'outil : état Terminé (ic_check ok) ou en cours (spinner). */
    private void appendToolCard(String label, boolean running, boolean isError) {
        CarteOutilBinding tool = CarteOutilBinding.inflate(getLayoutInflater());
        tool.toolLabel.setText(label);
        int cote = (int) (14 * getResources().getDisplayMetrics().density);
        if (running) {
            // Spinner : anneau primaire 14dp (indéterminé), à la place de l'icône.
            tool.toolIcon.setVisibility(View.INVISIBLE);
            ImageView spinner = new ImageView(requireContext());
            spinner.setImageResource(R.drawable.fond_spinner);
            LinearLayout.LayoutParams spinParams = new LinearLayout.LayoutParams(cote, cote);
            spinParams.setMarginEnd(
                    (int) (8 * getResources().getDisplayMetrics().density));
            ((LinearLayout) tool.getRoot()).addView(spinner, indexOfIcon(tool), spinParams);
            tool.toolSide.setText("");
        } else {
            tool.toolIcon.setImageResource(R.drawable.ic_check);
            tool.toolIcon.setColorFilter(
                    getResources().getColor(isError ? R.color.erreur : R.color.ok));
            tool.toolSide.setText("");
        }
        appendView(tool.getRoot());
    }

    /** Position de l'icône dans la carte d'outil (premier enfant). */
    private int indexOfIcon(CarteOutilBinding tool) {
        LinearLayout row = (LinearLayout) tool.getRoot();
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) == tool.toolIcon) {
                return i;
            }
        }
        return 0;
    }

    /** Carte question (studio : conteneur_tertiaire, chips sc_highest). */
    private void renderQuestion(String title, String body, String[] choices) {
        CarteQuestionBinding question = CarteQuestionBinding.inflate(getLayoutInflater());
        question.questionTitle.setText(title);
        question.questionBody.setText(body);
        for (String choice : choices) {
            ChipActionBinding chip = ChipActionBinding.inflate(getLayoutInflater());
            chip.choiceChip.setText(choice);
            chip.choiceChip.setOnClickListener(v -> ask(choice, true));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.setMarginEnd(
                    (int) (8 * getResources().getDisplayMetrics().density));
            question.questionChoices.addView(chip.getRoot(), chipParams);
        }
        appendView(question.getRoot());
    }

    /** Carte erreur (studio : conteneur_erreur, ic_bell, chips Réessayer / Réglages). */
    private void renderError(String body, String rawMessage) {
        CarteErreurBinding error = CarteErreurBinding.inflate(getLayoutInflater());
        error.errorTitle.setText(getString(R.string.chat_error_title));
        error.errorBody.setText(rawMessage != null && rawMessage.contains("401")
                ? getString(R.string.chat_error_body_401) : body);
        error.errorRetry.setOnClickListener(v -> {
            binding.transcriptList.removeView(error.getRoot());
            if (lastQuestion != null) {
                ask(lastQuestion, false);
            }
        });
        error.errorSettings.setOnClickListener(v -> openSettingsIa());
        appendView(error.getRoot());
    }

    /** Pose une vue dans le transcript et déroule vers le bas. */
    private void appendView(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
        binding.transcriptList.addView(view, params);
        scrollDown();
    }

    private void scrollDown() {
        binding.transcriptScroll.post(() ->
                binding.transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Invite d'approbation : le contexte AVANT d'agir vit DANS l'invite (REF-117). */
    private void promptPermission(PermissionGate.PermissionRequest request,
            java.util.function.Consumer<jo.joide.ai.InteractivePermissionGate.Decision> onAnswer) {
        StringBuilder message = new StringBuilder(
                getString(R.string.ia_permission_titre))
                .append("\n").append(request.tool()).append(" — ").append(request.path());
        if (request.preview() != null && !request.preview().isEmpty()) {
            message.append("\n\n").append(request.preview());
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ia_permission_titre)
                .setMessage(message)
                .setNegativeButton(R.string.ia_permission_refuser,
                        (d, w) -> onAnswer.accept(
                                jo.joide.ai.InteractivePermissionGate.Decision.DENY))
                .setPositiveButton(R.string.ia_permission_unefois,
                        (d, w) -> onAnswer.accept(
                                jo.joide.ai.InteractivePermissionGate.Decision.ALLOW_ONCE))
                .setNeutralButton(R.string.ia_permission_session,
                        (d, w) -> onAnswer.accept(
                                jo.joide.ai.InteractivePermissionGate.Decision.ALLOW_SESSION))
                .setOnCancelListener(d -> onAnswer.accept(
                        jo.joide.ai.InteractivePermissionGate.Decision.DENY))
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        thinkingBinding = null;
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
