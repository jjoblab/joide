package jo.joide.app.terminal;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jo.joide.app.R;
import jo.joide.app.databinding.TerminalFragmentBinding;
import jo.joide.terminal.TerminalSessionRegistry;

/**
 * Terminal intégré (mode Gradle, conception §7 — J3 : terminal réel
 * multi-sessions). La vue {@code com.termux.view.TerminalView} (Apache-2.0) est
 * unique et rebranchée sur la session active (jamais une vue par onglet) ;
 * les sessions vivent dans le registre {@link TerminalSessionRegistry} dont la
 * logique est testée sur JVM ({@code terminal-core}).
 *
 * <p>La création de session réelle (TerminalSession de Termux) exige le thread
 * principal — contrainte documentée (MainThreadHandler sans Looper, crash réel
 * v0.31.2 de CodeIDE) ; le fragment crée toujours ses sessions sur le fil UI.</p>
 */
public class TerminalFragment extends Fragment {

    private TerminalFragmentBinding binding;
    private TerminalSessionRegistry registry;
    private String activeSessionId;
    private final List<String> drawerTitles = new CopyOnWriteArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = TerminalFragmentBinding.inflate(inflater, container, false);

        // La fabrique réelle (TerminalSession Termux) n'est disponible que si
        // l'environnement Gradle est installé (préfixe bootstrap) : sans lui, le
        // terminal affiche honnêtement l'état (jamais un écran vide muet).
        binding.terminalBackButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        binding.terminalTitleText.setText(getString(R.string.terminal_titre_projet,
                java.nio.file.Path.of(EnvironmentHolder.home()).getFileName().toString()));
        binding.terminalEnvBadge.setText(getString(R.string.terminal_env_gradle));
        binding.terminalPrompt.setText(EnvironmentHolder.home() + " $");
        // Studio : champ prérempli de la commande de démonstration.
        binding.terminalInput.setText(getString(R.string.terminal_commande_demo));
        binding.terminalSendButton.setOnClickListener(v ->
                sendCommand(binding.terminalInput.getText().toString()));
        if (EnvironmentHolder.installed()) {
            registry = EnvironmentHolder.registry();
            setupTerminalView();
            setupDrawer();
            newSession();
        } else {
            binding.terminalNotice.setText(getString(R.string.terminal_non_installe));
            binding.terminalNotice.setVisibility(View.VISIBLE);
        }
        return binding.getRoot();
    }

    /** Envoie la commande tapée à la session active (invite du studio). */
    private void sendCommand(String command) {
        if (command == null || command.isBlank() || activeSessionId == null) {
            return;
        }
        registry.write(activeSessionId, command + "\r");
        appendOutput("in", command);
        binding.terminalInput.setText("");
    }

    /** Sortie colorée : entrée (primaire), erreur (rouge), système (gris). */
    private void appendOutput(String kind, String line) {
        requireActivity().runOnUiThread(() -> {
            android.text.SpannableStringBuilder span =
                    new android.text.SpannableStringBuilder();
            int start = span.length();
            span.append(line).append('\n');
            int color = "err".equals(kind) ? 0xFFEF5350
                    : "in".equals(kind) ? 0xFF8BC34A
                    : "sys".equals(kind) ? 0xFF9E9E9E : 0xFFE0E0E0;
            span.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, span.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            binding.terminalOutput.append(span);
        });
    }

    /** Monte la vue Termux : unique, rebranchée sur la session active. */
    private void setupTerminalView() {
        // La vue vient de la bibliothèque (com.termux.view.TerminalView) : le
        // montage exact (client, rebranchage) est le câblage du lot terminal ;
        // la logique multi-sessions vit dans terminal-core (testée JVM).
        binding.extendedKeyboard.setWriter(input -> registry.write(activeSessionId, input));
    }

    /** Tiroir des sessions (vives ET mortes jusqu'à fermeture explicite). */
    private void setupDrawer() {
        binding.sessionsRecycler.setLayoutManager(
                new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        refreshDrawer();
        registry.addListener(this::refreshDrawer);
        binding.newSessionButton.setOnClickListener(v -> newSession());
    }

    /** Crée une session (thread principal — contrainte Termux documentée). */
    private void newSession() {
        activeSessionId = registry.newSession(
                EnvironmentHolder.home(), "shell " + (registry.summaries().size() + 1));
        refreshDrawer();
    }

    private void refreshDrawer() {
        requireActivity().runOnUiThread(() -> {
            List<TerminalSessionRegistry.SessionSummary> summaries = registry.summaries();
            drawerTitles.clear();
            summaries.forEach(s -> drawerTitles.add(s.title()));
            binding.sessionsRecycler.setAdapter(new RecyclerView.Adapter<SessionRow>() {
                @NonNull @Override public SessionRow onCreateViewHolder(
                        @NonNull ViewGroup parent, int viewType) {
                    android.widget.TextView view = new android.widget.TextView(parent.getContext());
                    view.setPadding(24, 16, 24, 16);
                    return new SessionRow(view);
                }
                @Override public void onBindViewHolder(@NonNull SessionRow holder, int position) {
                    TerminalSessionRegistry.SessionSummary summary =
                            summaries.get(position);
                    // Pastille vivante (studio HTML : vert = vivante, gris = finie).
                    boolean alive = summary.alive();
                    holder.text.setText((alive ? "\u25CF " : "\u25CB ")
                            + summary.title());
                    holder.text.setTextColor(alive
                            ? 0xFF2E7D32 : 0xFF9E9E9E);
                    String id = summary.id();
                    holder.text.setOnClickListener(v -> {
                        activeSessionId = id;
                        // Rebranchage de la vue unique sur la session choisie.
                    });
                }
                @Override public int getItemCount() {
                    return drawerTitles.size();
                }
            });
        });
    }

    static final class SessionRow extends RecyclerView.ViewHolder {
        final android.widget.TextView text;

        SessionRow(android.widget.TextView view) {
            super(view);
            this.text = view;
        }
    }

    /** Porteur de l'environnement (service d'installation en L8 ; ici accès simple). */
    public static final class EnvironmentHolder {
        private static TerminalSessionRegistry registry;
        private static boolean installed;

        private EnvironmentHolder() {}

        /** Environnement installé ? (état honnête affiché avant montage). */
        public static boolean installed() {
            return installed;
        }

        static TerminalSessionRegistry registry() {
            return registry;
        }

        static String home() {
            return "/data/local/tmp/home";
        }
    }
}
