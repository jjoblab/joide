package jo.joide.app.ai;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jo.joide.app.R;
import jo.joide.app.databinding.AiPanelFragmentBinding;
import jo.joide.app.databinding.BulleReflexionBinding;
import jo.joide.app.databinding.BulleUserBinding;
import jo.joide.app.databinding.CarteErreurBinding;
import jo.joide.app.databinding.CarteOutilBinding;
import jo.joide.app.databinding.CarteQuestionBinding;
import jo.joide.app.databinding.ChipActionBinding;
import jo.joide.ai.AgentLoop;
import jo.joide.ai.AgentWorkspace;
import jo.joide.ai.AiProvider;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.ai.PermissionGate;

/**
 * Tiroir IA de l'atelier (conception §8 : le module `ai` est consommé par le
 * tiroir, activable/désactivable dans les paramètres — REF-101). Le réseau ne
 * tourne jamais sur le fil UI (§10) : la boucle s'exécute sur son pool mono-
 * thread, les deltas reviennent sur le fil UI.
 *
 * <p>Rendu studio (joide-previews-ecrans.html, chatScreen + drawer IA de
 * editorScreen) : barre à pastille conteneur_primaire + pills modèle /
 * permission, transcript à bulles utilisateur (sc2), carte de réflexion
 * (sc_high + barre 7×11 primaire), cartes d'outils (sc_high rayon 8, état
 * spinner), carte question (conteneur_tertiaire) et carte erreur
 * (conteneur_erreur) avec chips d'action.</p>
 *
 * <p>Critère du lot : conversation avec un fournisseur (mock en test),
 * approbation des actions de l'agent (porte unique : demander à chaque fois /
 * accepter / plan seulement), clés au coffre chiffré ({@link EncryptedKeyVault}).</p>
 */
public final class AiPanelFragment extends Fragment {

    private static final String ARG_PROJECT = "project";

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
     * Assistant général (accueil) : conversation sans projet — aucun accès aux
     * fichiers, rien à approuver.
     */
    public static AiPanelFragment assistant() {
        return new AiPanelFragment();
    }

    /** Chemin du projet de l'agent ; null pour l'assistant général. */
    @Nullable
    private String projectRoot() {
        Bundle args = getArguments();
        return args != null ? args.getString(ARG_PROJECT, null) : null;
    }

    private AiPanelFragmentBinding binding;
    private String lastQuestion;
    private PermissionGate gate;
    private int toolCount;
    private int fileCount;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = AiPanelFragmentBinding.inflate(inflater, container, false);
        // Deux rôles distincts : l'agent du projet (titre « Agent · nom », porte de
        // permission) et l'assistant général de l'accueil (sans fichiers, sans permission).
        String root = projectRoot();
        if (root != null) {
            binding.chatTitle.setText(getString(R.string.chat_agent_title,
                    new java.io.File(root).getName()));
        } else {
            binding.permPill.setVisibility(View.GONE);
        }

        // Composer : envoyer la demande (bouton rond 48 primaire du studio).
        binding.sendButton.setOnClickListener(v -> ask(binding.inputField
                .getText().toString()));
        // Pills de la barre : le modèle ouvre la page Réglage IA, la permission
        // cycle les trois modes réels de la porte partagée (REF-117).
        binding.modelPill.setOnClickListener(v -> openSettingsIa());
        binding.permPill.setOnClickListener(v -> cyclePermissionMode());
        binding.moreButton.setOnClickListener(v -> openSettingsIa());

        // État honnête : sans clé au coffre, le tiroir le dit au lieu d'échouer
        // en silence (le réglage des fournisseurs est à la page Réglage IA).
        EncryptedKeyVault vault = new EncryptedKeyVault(requireContext());
        if (vault.apiKey("laboratoire") == null && vault.apiKey("passerelle") == null) {
            renderError(getString(R.string.chat_no_key), null);
        }
        return binding.getRoot();
    }

    /** Ouvre la page Réglage IA (fournisseurs, clé, modèle) dans le même hôte. */
    private void openSettingsIa() {
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(),
                        new jo.joide.app.settings.SettingsIaPageFragment())
                .addToBackStack(null)
                .commit();
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

    @Override
    public void onResume() {
        super.onResume();
        renderPermissionPill();
        renderModelPill();
    }

    /** Pill modèle : identifiant persisté du fournisseur actif (page IA). */
    private void renderModelPill() {
        String provider = requireContext()
                .getSharedPreferences(jo.joide.app.settings.SettingsIaPageFragment.IA_PREFS,
                        android.content.Context.MODE_PRIVATE)
                .getString("fournisseur", "laboratoire");
        binding.modelPill.setText(getString(R.string.chat_model_pill,
                "passerelle".equals(provider) ? "modele-local" : "claude-4"));
    }

    /** Demande de l'utilisateur : un tour de la boucle d'agent. */
    private void ask(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        lastQuestion = message;
        toolCount = 0;
        fileCount = 0;
        binding.inputField.setText("");

        // Bulle utilisateur (studio : sc2 à droite, rayons 20/20/6/20).
        BulleUserBinding user = BulleUserBinding.inflate(getLayoutInflater());
        user.userBubble.setText(message);
        appendView(user.getRoot());

        // Carte de réflexion (studio : sc_high rayon 14, « En réflexion… »).
        startThinking();

        // Porte interactive partagée avec les paramètres (porte unique, REF-117) :
        // le décideur affiche le dialogue AVEC le diff dans l'invite, et rend
        // refuser / permettre une fois / permettre pour la session ; la fermeture
        // du dialogue vaut refus (défaut sûr).
        jo.joide.ai.InteractivePermissionGate interactiveGate =
                jo.joide.app.settings.SettingsFragment.sharedGate();
        if (interactiveGate instanceof PermissionGate) {
            gate = interactiveGate;
            interactiveGate.setDecider((request, onAnswer) ->
                    requireActivity().runOnUiThread(() ->
                            promptPermission(request, onAnswer)));
        }

        // La boucle tourne hors fil UI (pool dédié — conception §10).
        new Thread(() -> {
            String projectRoot = projectRoot();
            AgentWorkspace workspace = projectRoot != null
                    ? new AgentWorkspace.OnProject(
                            new java.io.File(projectRoot).toPath())
                    : new AgentWorkspace.InMemory();
            HttpLlmTransport transport = new HttpLlmTransport();
            AgentLoop loop = new AgentLoop(AiProvider.gateway(transport), transport,
                    "http://localhost:11434", null, "modele-local",
                    workspace, gate, 16);
            loop.setProjectContext(projectRoot != null
                    ? "Projet : " + projectRoot
                    : getString(R.string.chat_assistant_contexte));
            List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
            loop.turn(message, events::add);
            requireActivity().runOnUiThread(() -> events.forEach(this::render));
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
            appendToolCard(result.summary(), false, result.isError());
        } else if (event instanceof AgentLoop.LoopEvent.TurnCompleted completed) {
            endThinking();
            renderUsage(completed.iterations());
        } else if (event instanceof AgentLoop.LoopEvent.TurnFailed failure) {
            endThinking();
            // Studio : l'échec de tour rend la carte 401 (clé rejetée, actions).
            String reason = failure.message() != null && failure.message().contains("401")
                    ? failure.message() : getString(R.string.chat_error_body_401);
            renderError(reason, failure.message());
        }
    }

    /** Compteur du tour (carte d'outil : « N outils · M fichiers » du studio). */
    private void renderUsage(int iterations) {
        if (toolCount > 0) {
            appendToolCard(getString(R.string.chat_tool_files, toolCount, fileCount),
                    false, false);
        }
    }

    /** Amorce du transcript vide : rien (le vrai transcript part d'ask()). */

    // ---- Événements de boucle : fabrique de vues --------------------------------

    /** Carte de réflexion (studio : fond sc_high rayon 14, barre 7×11 primaire). */
    private void startThinking() {
        endThinking();
        thinkingBinding = BulleReflexionBinding.inflate(getLayoutInflater());
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

    /** Ferme la carte de réflexion (fin de tour ou nouvelle carte). */
    private void endThinking() {
        if (thinkingBinding != null) {
            binding.transcriptList.removeView(thinkingBinding.getRoot());
            thinkingBinding = null;
        }
    }

    /** Carte d'outil : état Terminé (ic_check ok + « +N −M lignes ») ou en cours (spinner). */
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
            toolCount++;
        } else {
            tool.toolIcon.setImageResource(R.drawable.ic_check);
            tool.toolIcon.setColorFilter(
                    getResources().getColor(isError ? R.color.erreur : R.color.ok));
            tool.toolSide.setText("");
            toolCount++;
            fileCount++;
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
            chip.choiceChip.setOnClickListener(v -> ask(choice));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.setMarginEnd(
                    (int) (8 * getResources().getDisplayMetrics().density));
            question.questionChoices.addView(chip.getRoot(), chipParams);
        }
        appendView(question.getRoot());
    }

    /** Carte erreur (studio : conteneur_erreur, ic_bell, chips Réessayer / Ouvrir Paramètres). */
    private void renderError(String body, String rawMessage) {
        CarteErreurBinding error = CarteErreurBinding.inflate(getLayoutInflater());
        error.errorTitle.setText(getString(R.string.chat_error_title));
        error.errorBody.setText(rawMessage != null && rawMessage.contains("401")
                ? getString(R.string.chat_error_body_401) : body);
        error.errorRetry.setOnClickListener(v -> {
            binding.transcriptList.removeView(error.getRoot());
            if (lastQuestion != null) {
                ask(lastQuestion);
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

    /** Dialogue d'approbation : le diff AVANT d'agir vit DANS l'invite (REF-117). */
    private void promptPermission(PermissionGate.PermissionRequest request,
            java.util.function.Consumer<jo.joide.ai.InteractivePermissionGate.Decision> onAnswer) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(
                requireContext());
        StringBuilder message = new StringBuilder(
                getString(R.string.ia_permission_titre))
                .append("\n").append(request.tool()).append(" — ").append(request.path());
        if (request.preview() != null && !request.preview().isEmpty()) {
            message.append("\n\n").append(request.preview());
        }
        builder.setTitle(R.string.ia_permission_titre)
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
                        jo.joide.ai.InteractivePermissionGate.Decision.DENY));
        builder.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        thinkingBinding = null;
    }
}
