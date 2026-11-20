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

    private TextView conversationView;
    private StringBuilder transcript = new StringBuilder();
    private PermissionGate gate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        conversationView = new TextView(requireContext());
        conversationView.setPadding(24, 16, 24, 16);
        conversationView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(conversationView);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint("demander à l'IA…");
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button send = new Button(requireContext());
        send.setText("Envoyer");
        send.setOnClickListener(v -> ask(input.getText().toString()));
        root.addView(send, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // État honnête : sans clé au coffre, le tiroir le dit au lieu d'échouer
        // en silence (le réglage des fournisseurs est au lot paramètres L8).
        EncryptedKeyVault vault = new EncryptedKeyVault(requireContext());
        if (vault.apiKey("laboratoire") == null && vault.apiKey("passerelle") == null) {
            appendToTranscript(getString(R.string.ia_sans_cle));
        }
        return root;
    }

    /** Demande de l'utilisateur : un tour de la boucle d'agent. */
    private void ask(String message) {
        if (message.isBlank()) {
            return;
        }
        appendToTranscript("\n> " + message);

        // Porte réelle de la coquille : demander à chaque fois (défaut sûr),
        // l'approbation par l'utilisateur arrive avec le lot paramètres (L8) —
        // la porte reste le SEUL point de décision (REF-117).
        gate = gate != null ? gate
                : new PermissionGate() {
                    @Override public Mode mode() { return Mode.ASK_EACH; }
                    @Override public boolean authorize(PermissionRequest request) {
                        // Tiroir L7 : l'approbation interactive (diff dans l'invite)
                        // est portée par la section paramètres du lot L8 ; en
                        // attendant le mode sûr = refus informatif au modèle.
                        requireActivity().runOnUiThread(() ->
                                appendToTranscript("\n[mutation « " + request.tool()
                                        + " » sur " + request.path()
                                        + " : approuvez-la dans les paramètres IA]"));
                        return false;
                    }
                };

        // La boucle tourne hors fil UI (pool dédié — conception §10).
        new Thread(() -> {
            String projectRoot = requireArguments().getString("project", null);
            AgentWorkspace workspace = projectRoot != null
                    ? new AgentWorkspace.OnProject(java.nio.file.Path.of(projectRoot))
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

    /** Rendu d'un événement de boucle dans le tiroir (deltas, outils, refus). */
    private void render(AgentLoop.LoopEvent event) {
        if (event instanceof AgentLoop.LoopEvent.Delta delta) {
            appendToTranscript(delta.text());
        } else if (event instanceof AgentLoop.LoopEvent.ToolStarted started) {
            appendToTranscript("\n[outil " + started.name() + "]");
        } else if (event instanceof AgentLoop.LoopEvent.ToolResult result) {
            appendToTranscript(result.isError()
                    ? " (refusé/informé)" : " (" + result.summary() + ")");
        } else if (event instanceof AgentLoop.LoopEvent.TurnCompleted completed) {
            appendToTranscript("\n[tour terminé, " + completed.iterations() + " itération(s)]");
        } else if (event instanceof AgentLoop.LoopEvent.TurnFailed failure) {
            appendToTranscript("\n[échec : " + failure.message() + "]");
        }
    }

    private void appendToTranscript(String line) {
        transcript.append(line);
        conversationView.setText(transcript.toString());
    }
}
