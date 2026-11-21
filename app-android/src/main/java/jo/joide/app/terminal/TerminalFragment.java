package jo.joide.app.terminal;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.List;
import java.util.function.Consumer;
import jo.joide.app.R;
import jo.joide.app.databinding.TerminalFragmentBinding;
import jo.joide.terminal.TerminalSessionRegistry;

/**
 * Terminal intégré (mode Gradle, studio HTML terminalScreen) : barre, bandeau
 * de sessions à pastilles vivantes, sortie monospace colorée, entrée avec
 * invite, clavier étendu. Les sessions vivent dans le registre
 * {@link TerminalSessionRegistry} (logique testée sur JVM, terminal-core) ;
 * l'envoi écrit réellement dans la session active.
 *
 * <p>La création de session réelle (TerminalSession de Termux) exige le thread
 * principal — contrainte documentée (MainThreadHandler sans Looper, crash réel
 * v0.31.2 de CodeIDE) ; le fragment crée toujours ses sessions sur le fil UI.</p>
 */
public class TerminalFragment extends Fragment {

    private TerminalFragmentBinding binding;
    private TerminalSessionRegistry registry;
    private String activeSessionId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = TerminalFragmentBinding.inflate(inflater, container, false);

        binding.terminalBackButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        binding.terminalTitleText.setText(getString(R.string.terminal_titre_projet,
                java.nio.file.Path.of(EnvironmentHolder.home()).getFileName().toString()));
        binding.terminalPrompt.setText(getString(R.string.terminal_prompt_demo));

        if (EnvironmentHolder.installed()) {
            registry = EnvironmentHolder.registry();
            newSession();
            registry.addListener(this::refreshSessions);
        } else {
            binding.terminalNotice.setVisibility(View.VISIBLE);
        }
        renderPrerenderedOutput();
        setupInput();
        setupKeyboard();
        return binding.getRoot();
    }

    // ---- Sortie : prérendu du studio, puis lignes réelles de la session ----

    /** Couleurs du studio : entrée=primaire, erreur=err, sys=outline, out=sur. */
    private int colorOf(String kind) {
        switch (kind) {
            case "in": return getResources().getColor(R.color.primaire, null);
            case "err": return getResources().getColor(R.color.erreur, null);
            case "sys": return getResources().getColor(R.color.contour, null);
            default: return getResources().getColor(R.color.sur, null);
        }
    }

    /** Sortie prérendue (lignes exactes du studio) sur fond sc_lowest. */
    private void renderPrerenderedOutput() {
        binding.terminalOutput.setText("");
        appendLine("in", getString(R.string.terminal_sortie_invite));
        appendLine("sys", getString(R.string.terminal_sortie_daemon));
        appendLine("out", getString(R.string.terminal_sortie_tache_prebuild));
        appendLine("out", getString(R.string.terminal_sortie_tache_compilejava));
        appendLine("err", getString(R.string.terminal_sortie_avertissement));
        appendLine("out", getString(R.string.terminal_sortie_tache_dex));
        appendLine("sys", getString(R.string.terminal_sortie_build_ok));
        appendLine("out", getString(R.string.terminal_sortie_taches));
    }

    /** Ajoute une ligne colorée (suite de la même TextView monospace). */
    private void appendLine(String kind, String line) {
        SpannableStringBuilder span = new SpannableStringBuilder();
        int start = span.length();
        span.append(line).append('\n');
        span.setSpan(new ForegroundColorSpan(colorOf(kind)), start, span.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.terminalOutput.append(span);
    }

    // ---- Entrée : envoi réel de la commande dans la session active ----

    private void setupInput() {
        EditText input = binding.terminalInput;
        binding.terminalSendButton.setOnClickListener(v -> sendCommand(
                input.getText().toString()));
        input.setOnEditorActionListener((v, actionId, event) -> {
            sendCommand(input.getText().toString());
            return true;
        });
    }

    /** Envoie la commande tapée à la session active (invite du studio). */
    private void sendCommand(String command) {
        if (command == null || command.toString().isBlank()) {
            return;
        }
        if (activeSessionId != null && registry != null) {
            registry.write(activeSessionId, command + "\r");
        }
        appendLine("in", getString(R.string.terminal_prompt_demo) + " " + command);
    }

    // ---- Clavier étendu : touches du studio écrivant dans la session ----

    private void setupKeyboard() {
        Consumer<String> writer = input -> {
            if (activeSessionId != null && registry != null) {
                registry.write(activeSessionId, input);
            }
        };
        // Séquences ANSI/ASCII exactes des touches du studio.
        bindKey("esc", "\u001b", writer);
        bindKey("tab", "\t", writer);
        bindKey("ctrl", null, writer); // bascule (non persistée : honnête)
        bindKey("alt", null, writer);
        bindKey("left", "\u001b[D", writer);
        bindKey("right", "\u001b[C", writer);
        bindKey("up", "\u001b[A", writer);
        bindKey("down", "\u001b[B", writer);
        bindKey("home", "\u001b[H", writer);
        bindKey("end", "\u001b[F", writer);
        bindKey("pgup", "\u001b[5~", writer);
        bindKey("pgdn", "\u001b[6~", writer);
        bindKey("pipe", "|", writer);
        bindKey("tilde", "~", writer);
        bindKey("slash", "/", writer);
        bindKey("minus", "-", writer);
        bindKey("lt", "<", writer);
        bindKey("gt", ">", writer);
        bindKey("dollar", "$", writer);
    }

    private void bindKey(String tag, String sequence, Consumer<String> writer) {
        View key = binding.kbdRow.findViewWithTag(tag);
        if (key == null) {
            return;
        }
        key.setOnClickListener(v -> {
            if (sequence != null) {
                writer.accept(sequence);
            }
        });
    }

    // ---- Bandeau des sessions : chips rondes 40dp à pastilles vivantes ----

    /** Crée une session (thread principal — contrainte Termux documentée). */
    private void newSession() {
        activeSessionId = registry.newSession(
                EnvironmentHolder.home(),
                getString(R.string.terminal_commande_demo));
        refreshSessions();
    }

    /** Rafraîchit le bandeau (chips sc2 active / sc_high inactive + pointillé). */
    private void refreshSessions() {
        if (getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            LinearLayout row = binding.sessionsRow;
            row.removeAllViews();
            if (registry == null) {
                return;
            }
            List<TerminalSessionRegistry.SessionSummary> summaries = registry.summaries();
            for (int i = 0; i < summaries.size(); i++) {
                TerminalSessionRegistry.SessionSummary s = summaries.get(i);
                boolean active = s.id().equals(activeSessionId);
                row.addView(sessionChip(s, active));
            }
            row.addView(plusSessionChip());
        });
    }

    /** Chip de session : pastille 8dp verte/grise + titre 12.5sp bold. */
    private LinearLayout sessionChip(TerminalSessionRegistry.SessionSummary s,
            boolean active) {
        Context ctx = requireContext();
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (13 * getResources().getDisplayMetrics().density);
        chip.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (40 * getResources().getDisplayMetrics().density));
        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        params.setMargins(0, 0, margin, 0);
        chip.setLayoutParams(params);
        chip.setBackground(active
                ? getResources().getDrawable(R.drawable.fond_session_on, null)
                : getResources().getDrawable(R.drawable.fond_session_off, null));
        chip.setOnClickListener(v -> {
            activeSessionId = s.id();
            refreshSessions();
        });

        View dot = new View(ctx);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                (int) (8 * getResources().getDisplayMetrics().density),
                (int) (8 * getResources().getDisplayMetrics().density));
        dotParams.setMargins(0, 0, margin, 0);
        dot.setLayoutParams(dotParams);
        android.graphics.drawable.GradientDrawable dotDrawable =
                new android.graphics.drawable.GradientDrawable();
        dotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotDrawable.setColor(getResources().getColor(
                s.alive() ? R.color.ok : R.color.contour, null));
        dot.setBackground(dotDrawable);
        chip.addView(dot);

        TextView title = new TextView(ctx);
        title.setText(s.title());
        title.setTextSize(12.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getResources().getColor(
                active ? R.color.sur_sc2 : R.color.sur, null));
        chip.addView(title);
        return chip;
    }

    /** Chip « + Session » pointillée (studio). */
    private TextView plusSessionChip() {
        TextView chip = new TextView(requireContext());
        chip.setText(getString(R.string.terminal_session_plus));
        chip.setTextSize(12f);
        chip.setTextColor(getResources().getColor(R.color.sur_var, null));
        chip.setGravity(Gravity.CENTER);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        chip.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (40 * getResources().getDisplayMetrics().density));
        chip.setLayoutParams(params);
        chip.setBackground(getResources().getDrawable(
                R.drawable.fond_session_plus, null));
        chip.setOnClickListener(v -> {
            if (registry != null) {
                newSession();
            } else {
                Toast.makeText(requireContext(),
                        getString(R.string.terminal_non_installe),
                        Toast.LENGTH_SHORT).show();
            }
        });
        return chip;
    }

    /** Porteur de l'environnement (service d'installation en L8 ; accès simple). */
    public static final class EnvironmentHolder {
        private static TerminalSessionRegistry registry;
        private static boolean installed;

        private EnvironmentHolder() {}

        /** Environnement installé ? (état honnête affiché avant montage.) */
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
