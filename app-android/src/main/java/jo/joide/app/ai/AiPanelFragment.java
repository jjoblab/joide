package jo.joide.app.ai;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jo.joide.app.R;
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
 * <p>Critère du lot : conversation avec un fournisseur (mock en test), appro-
 * bation des actions de l'agent (porte unique : demander à chaque fois /
 * accepter / plan seulement), clés au coffre chiffré ({@link EncryptedKeyVault}).</p>
 */
public final class AiPanelFragment extends Fragment {

    private LinearLayout conversationList;
    private ScrollView conversationScroll;
    private android.widget.EditText input;
    private Button sendButton;
    private String lastQuestion;
    private PermissionGate gate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // Conversation à bulles (studio HTML, onglet Chat IA) : question de
        // l'utilisateur, réponse en cours (réflexion), cartes d'outils, erreur.
        conversationList = new LinearLayout(requireContext());
        conversationList.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        conversationList.setPadding(pad, pad, pad, pad);
        conversationScroll = new ScrollView(requireContext());
        conversationScroll.addView(conversationList);
        root.addView(conversationScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        input = new android.widget.EditText(requireContext());
        input.setHint(getString(R.string.ia_hint_demande));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sendButton = new Button(requireContext());
        sendButton.setText(getString(R.string.ia_envoyer));
        sendButton.setOnClickListener(v -> ask(input.getText().toString()));
        root.addView(sendButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // État honnête : sans clé au coffre, le tiroir le dit au lieu d'échouer
        // en silence (le réglage des fournisseurs est au lot paramètres L8).
        EncryptedKeyVault vault = new EncryptedKeyVault(requireContext());
        if (vault.apiKey("laboratoire") == null && vault.apiKey("passerelle") == null) {
            appendBubble(getString(R.string.ia_sans_cle), BubbleKind.ERROR);
        }
        return root;
    }

    /** Nature d'une bulle (styles du studio : utilisateur, réflexion, outil, erreur). */
    enum BubbleKind { USER, THINKING, TOOL, ERROR }

    /** Demande de l'utilisateur : un tour de la boucle d'agent. */
    private void ask(String message) {
        if (message.isBlank()) {
            return;
        }
        lastQuestion = message;
        appendBubble(message, BubbleKind.USER);
        thinkingBubble();

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
            String projectRoot = requireArguments().getString("project", null);
            AgentWorkspace workspace = projectRoot != null
                    ? new AgentWorkspace.OnProject(
                            new java.io.File(projectRoot).toPath())
                    : new AgentWorkspace.InMemory();
            HttpLlmTransport transport = new HttpLlmTransport();
            AgentLoop loop = new AgentLoop(AiProvider.gateway(transport), transport,
                    "http://localhost:11434", null, "modele-local",
                    workspace, gate, 16);
            loop.setProjectContext("Projet : " + (projectRoot != null ? projectRoot : "(aucun)"));
            List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();
            loop.turn(message, events::add);
            requireActivity().runOnUiThread(() -> events.forEach(this::render));
        }, "agent-ia").start();
    }

    /** Rendu d'un événement de boucle : bulles typées (studio Chat IA). */
    private void render(AgentLoop.LoopEvent event) {
        if (event instanceof AgentLoop.LoopEvent.Delta delta) {
            appendBubble(delta.text(), BubbleKind.THINKING);
        } else if (event instanceof AgentLoop.LoopEvent.ToolStarted started) {
            appendBubble(getString(R.string.ia_outil_en_cours) + " · " + started.name(),
                    BubbleKind.TOOL);
        } else if (event instanceof AgentLoop.LoopEvent.ToolResult result) {
            appendBubble(getString(R.string.ia_outil_fait,
                    result.isError() ? "refusé" : "fait", result.summary()),
                    BubbleKind.TOOL);
        } else if (event instanceof AgentLoop.LoopEvent.TurnCompleted completed) {
            endThinking();
        } else if (event instanceof AgentLoop.LoopEvent.TurnFailed failure) {
            endThinking();
            // Studio : l'échec de tour rend la carte 401 (clé rejetée, actions).
            String reason = failure.message() != null && failure.message().contains("401")
                    ? failure.message() : getString(R.string.ia_erreur_401);
            appendBubble(reason, BubbleKind.ERROR);
        }
    }

    /** Dialogue d'approbation : le diff AVANT d'agir vit DANS l'invite (REF-117). */
    private void promptPermission(PermissionGate.PermissionRequest request,
            java.util.function.Consumer<jo.joide.ai.InteractivePermissionGate.Decision> onAnswer) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(
                requireContext());
        StringBuilder message = new StringBuilder("L'agent veut : ")
                .append(request.tool()).append(" — ").append(request.path());
        if (request.preview() != null && !request.preview().isEmpty()) {
            message.append("\n\nAperçu du fichier (avant) :\n")
                    .append(request.preview());
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

    /** Pose une bulle dans la conversation (l'utilisateur à droite, le reste à gauche). */
    private void appendBubble(String text, BubbleKind kind) {
        endThinking();
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        if (kind == BubbleKind.ERROR) {
            // Studio : carte d'erreur avec titre + actions Réessayer / Ouvrir Paramètres.
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad * 2, pad, pad * 2, pad);
            TextView title = new TextView(requireContext());
            title.setText(getString(R.string.ia_erreur_titre));
            title.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            card.addView(title);
            TextView detail = new TextView(requireContext());
            detail.setText(text);
            detail.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            card.addView(detail);
            LinearLayout actions = new LinearLayout(requireContext());
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button retry = new Button(requireContext());
            retry.setText(getString(R.string.ia_erreur_reessayer));
            retry.setOnClickListener(v -> {
                conversationList.removeView(card);
                ask(lastQuestion);
            });
            actions.addView(retry, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button open = new Button(requireContext());
            open.setText(getString(R.string.ia_erreur_parametres));
            open.setOnClickListener(v ->
                    getParentFragmentManager().beginTransaction()
                            .replace(((ViewGroup) requireView().getParent()).getId(),
                                    new jo.joide.app.settings.SettingsFragment())
                            .addToBackStack(null)
                            .commit());
            actions.addView(open, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(actions);
            conversationList.addView(card);
            conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
            return;
        }
        TextView bubble = new TextView(requireContext());
        bubble.setText(text);
        bubble.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        bubble.setPadding(pad * 2, pad, pad * 2, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = pad;
        bubble.setLayoutParams(params);
        if (kind == BubbleKind.USER) {
            params.gravity = android.view.Gravity.END;
        }
        conversationList.addView(bubble);
        conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView thinkingView;

    /** État « réponse en cours » : bulle de réflexion, remplacée à chaque delta. */
    private void thinkingBubble() {
        endThinking();
        thinkingView = new TextView(requireContext());
        thinkingView.setText(getString(R.string.ia_etat_reflexion));
        thinkingView.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        thinkingView.setPadding(pad * 2, pad, pad * 2, pad);
        conversationList.addView(thinkingView);
    }

    /** Ferme la bulle de réflexion (fin de tour ou nouvelle bulle). */
    private void endThinking() {
        if (thinkingView != null) {
            conversationList.removeView(thinkingView);
            thinkingView = null;
        }
    }
}
