package jo.joide.app.drawer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.StructureTabFragmentBinding;
import jo.joide.outline.Outline;

/**
 * Onglet Structure du tiroir gauche (Lot 5 de la refonte) : le plan du
 * fichier **actif** — Java/Kotlin (types, membres, visibilité + − # ~,
 * imports repliés), XML (éléments avec android:id), JSON (clés), Markdown
 * (titres) — calculé par le module core-outline, tolérant au code incomplet,
 * hors fil UI, rafraîchi quand le tampon change (suivi local léger, 700 ms).
 *
 * <p>Suit le curseur (le symbole courant est mis en évidence), tri par
 * position ou alphabétique, filtre rapide des symboles, appui → saut à la
 * ligne (contrat {@link WorkbenchHost#openFileAt}). Les états se disent :
 * aucun fichier ouvert, langage non pris en charge (un fournisseur LSP
 * pourra s'y brancher), plan indisponible. Le tiroir ne touche jamais
 * l'éditeur : tout passe par l'hôte.</p>
 */
public final class StructureTabFragment extends Fragment {

    private StructureTabFragmentBinding binding;
    private WorkspaceViewModel viewModel;
    private Path root;
    private SymbolsAdapter adapter;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable tick;

    /** Fichier planifié courant ; null = rien d'ouvert. */
    private Path outlinedFile;

    /** Texte du dernier plan (pour le suivi de curseur). */
    private String outlinedText = "";

    /** Symboles du plan courant. */
    private List<Outline.Symbol> symbols = new ArrayList<>();

    private boolean sortAlphabetical;
    private boolean followCursor = true;
    private boolean filterVisible;
    private String filterQuery = "";
    private int highlightedLine = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = StructureTabFragmentBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(requireParentFragment())
                .get(WorkspaceViewModel.class);
        String path = viewModel.projectPath();
        root = path != null ? Paths.get(path) : null;

        binding.structSortPosition.setOnClickListener(v -> {
            sortAlphabetical = false;
            renderSort();
            renderSymbols();
        });
        binding.structSortAz.setOnClickListener(v -> {
            sortAlphabetical = true;
            renderSort();
            renderSymbols();
        });
        binding.structFollow.setOnClickListener(v -> {
            followCursor = !followCursor;
            renderSort();
            if (!followCursor) {
                highlightedLine = -1;
                renderSymbols();
            }
        });
        binding.structFilter.setOnClickListener(v -> {
            filterVisible = !filterVisible;
            binding.structFilterField.setVisibility(
                    filterVisible ? View.VISIBLE : View.GONE);
            binding.structFilter.setColorFilter(getResources().getColor(
                    filterVisible ? R.color.primaire : R.color.sur_var));
            if (!filterVisible) {
                binding.structFilterField.setText("");
            }
        });
        binding.structFilterField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterQuery = s.toString().trim();
                renderSymbols();
            }
        });

        adapter = new SymbolsAdapter();
        binding.structList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.structList.setAdapter(adapter);

        renderSort();
        renderSymbols();
        return binding.getRoot();
    }

    /** Suivi local léger : fichier actif, tampon, curseur (700 ms). */
    @Override
    public void onResume() {
        super.onResume();
        tick = new Runnable() {
            @Override
            public void run() {
                if (binding == null || isHidden()) {
                    return;
                }
                pollWorkbench();
                main.postDelayed(this, 700);
            }
        };
        main.postDelayed(tick, 700);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (tick != null) {
            main.removeCallbacks(tick);
            tick = null;
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && isResumed() && tick == null) {
            onResume();
        }
    }

    private void pollWorkbench() {
        WorkbenchHost host = host();
        if (host == null) {
            return;
        }
        Path active = host.activeFile();
        String text = host.activeFileText();
        // Le tampon (ou le fichier) a changé → nouveau plan.
        if (active == null || text == null || root == null
                || !active.startsWith(root)) {
            if (outlinedFile != null) {
                outlinedFile = null;
                symbols = new ArrayList<>();
                renderSymbols();
            }
            return;
        }
        if (!active.equals(outlinedFile) || !text.equals(outlinedText)) {
            rebuildOutline(active, text);
            return;
        }
        if (followCursor) {
            int offset = host.cursorOffset();
            int line = lineOf(outlinedText, offset);
            if (line != highlightedLine) {
                highlightedLine = line;
                renderSymbols();
            }
        }
    }

    /** Reconstruit le plan hors fil UI (tolérant, jamais de crash). */
    private void rebuildOutline(Path file, String text) {
        outlinedFile = file;
        outlinedText = text;
        highlightedLine = -1;
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        Thread worker = new Thread(() -> {
            List<Outline.Symbol> built = null;
            if (lower.endsWith(".java") || lower.endsWith(".kt")) {
                built = Outline.javaOutline(text);
            } else if (lower.endsWith(".xml")) {
                built = Outline.xmlOutline(text);
            } else if (lower.endsWith(".json")) {
                built = Outline.jsonOutline(text);
            } else if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                built = Outline.markdownOutline(text);
            }
            List<Outline.Symbol> result = built;
            main.post(() -> {
                if (binding == null || !file.equals(outlinedFile)) {
                    return;
                }
                symbols = result == null ? new ArrayList<>() : result;
                renderSymbols();
            });
        }, "tiroir-outline");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- Rendu -----------------------------------------------------------------

    private void renderSort() {
        binding.structSortPosition.setBackground(getResources().getDrawable(
                sortAlphabetical ? R.drawable.fond_chip : R.drawable.fond_chip_on));
        binding.structSortPosition.setTextColor(getResources().getColor(
                sortAlphabetical ? R.color.sur : R.color.sur_sc2));
        binding.structSortAz.setBackground(getResources().getDrawable(
                sortAlphabetical ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        binding.structSortAz.setTextColor(getResources().getColor(
                sortAlphabetical ? R.color.sur_sc2 : R.color.sur));
        binding.structFollow.setColorFilter(getResources().getColor(
                followCursor ? R.color.primaire : R.color.sur_var));
    }

    private void renderSymbols() {
        if (binding == null) {
            return;
        }
        WorkbenchHost host = host();
        Path active = host != null ? host.activeFile() : null;
        binding.structFile.setText(active != null
                ? active.getFileName().toString() : "");
        if (adapter != null) {
            adapter.reload();
        }
    }

    /** Ligne 1-based d'un offset (−1 si inconnu). */
    private static int lineOf(String text, int offset) {
        if (offset < 0 || offset > text.length()) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Le symbole courant : la déclaration la plus proche au-dessus du curseur. */
    private Outline.Symbol currentSymbol() {
        if (highlightedLine < 0) {
            return null;
        }
        Outline.Symbol best = null;
        for (Outline.Symbol symbol : symbols) {
            if (symbol.line() <= highlightedLine) {
                best = symbol;
            } else {
                break;
            }
        }
        return best;
    }

    // ---- Adaptateur --------------------------------------------------------------

    private final class SymbolsAdapter extends RecyclerView.Adapter<SymbolHolder> {

        private final List<Object> items = new ArrayList<>();

        void reload() {
            List<Object> next = new ArrayList<>();
            WorkbenchHost host = host();
            Path active = host != null ? host.activeFile() : null;
            String text = host != null ? host.activeFileText() : null;
            if (active == null || text == null) {
                next.add(State.NO_FILE);
            } else if (symbols == null) {
                next.add(State.UNSUPPORTED);
            } else if (!supported(active)) {
                next.add(State.UNSUPPORTED);
            } else if (symbols.isEmpty()) {
                next.add(State.EMPTY);
            } else {
                Outline.Symbol current = currentSymbol();
                List<Outline.Symbol> shown = new ArrayList<>(symbols);
                if (!filterQuery.isEmpty()) {
                    String query = filterQuery.toLowerCase(Locale.ROOT);
                    shown.removeIf(symbol -> !symbol.name()
                            .toLowerCase(Locale.ROOT).contains(query));
                }
                if (sortAlphabetical) {
                    shown.sort(Comparator.comparing(
                            symbol -> symbol.name().toLowerCase(Locale.ROOT)));
                }
                for (Outline.Symbol symbol : shown) {
                    next.add(symbol.line() == current.line()
                            && symbol.name().equals(current.name())
                            ? new Highlighted(symbol) : symbol);
                }
            }
            List<Object> old = new ArrayList<>(items);
            items.clear();
            items.addAll(next);
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return next.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPos, int newPos) {
                    return keyOf(old.get(oldPos)).equals(keyOf(next.get(newPos)));
                }

                @Override
                public boolean areContentsTheSame(int oldPos, int newPos) {
                    return old.get(oldPos).equals(next.get(newPos));
                }
            }).dispatchUpdatesTo(this);
        }

        private String keyOf(Object item) {
            if (item instanceof Outline.Symbol symbol) {
                return "S:" + symbol.line() + ":" + symbol.name();
            }
            if (item instanceof Highlighted highlighted) {
                return "S:" + highlighted.symbol().line() + ":"
                        + highlighted.symbol().name();
            }
            return "E:" + item;
        }

        @NonNull
        @Override
        public SymbolHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout view = new LinearLayout(parent.getContext());
            view.setOrientation(LinearLayout.VERTICAL);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new SymbolHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SymbolHolder holder, int position) {
            Object item = items.get(position);
            if (item instanceof Outline.Symbol symbol) {
                holder.bind(symbol, false);
            } else if (item instanceof Highlighted highlighted) {
                holder.bind(highlighted.symbol(), true);
            } else {
                holder.bindState((State) item);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private enum State { NO_FILE, UNSUPPORTED, EMPTY }

    private record Highlighted(Outline.Symbol symbol) {}

    private static boolean supported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".kt")
                || name.endsWith(".xml") || name.endsWith(".json")
                || name.endsWith(".md") || name.endsWith(".markdown");
    }

    private final class SymbolHolder extends RecyclerView.ViewHolder {
        private final LinearLayout box;

        SymbolHolder(@NonNull LinearLayout view) {
            super(view);
            box = view;
        }

        void bind(Outline.Symbol symbol, boolean current) {
            box.removeAllViews();
            LinearLayout row = new LinearLayout(box.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int indent = dip(6) + symbol.depth() * dip(16);
            row.setPadding(indent, dip(4), dip(6), dip(4));
            if (current) {
                row.setBackground(getResources().getDrawable(R.drawable.fond_chip_on));
            }
            if (monogram(symbol.kind()) == 0) {
                // Types Java : vraies icônes d'arborescence (dessins du Lot 2).
                android.widget.ImageView iconView = new android.widget.ImageView(
                        box.getContext());
                iconView.setImageResource(FileTreeAdapter.iconRes(
                        fileKindOf(symbol.kind())));
                row.addView(iconView, new LinearLayout.LayoutParams(
                        dip(17), dip(17)));
            } else {
                TextView icon = new TextView(box.getContext());
                icon.setText(monogram(symbol.kind()));
                icon.setTextSize(9f);
                icon.setTypeface(Typeface.DEFAULT_BOLD);
                icon.setGravity(android.view.Gravity.CENTER);
                icon.setTextColor(getResources().getColor(
                        monogramColor(symbol.kind())));
                icon.setBackground(getResources().getDrawable(R.drawable.fond_badge));
                icon.setMinWidth(dip(17));
                icon.setMinHeight(dip(17));
                row.addView(icon);
            }
            TextView name = new TextView(box.getContext());
            SpannableText.of(name, symbol);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = dip(8);
            row.addView(name, params);
            if (symbol.statik() && symbol.kind() != Outline.Kind.PACKAGE
                    && symbol.kind() != Outline.Kind.IMPORTS) {
                TextView statik = new TextView(box.getContext());
                statik.setText("s");
                statik.setTextSize(9f);
                statik.setTypeface(Typeface.DEFAULT_BOLD, Typeface.ITALIC);
                statik.setTextColor(getResources().getColor(R.color.contour));
                row.addView(statik);
            }
            box.addView(row);
            box.setOnClickListener(v -> {
                WorkbenchHost host = host();
                if (host != null && outlinedFile != null) {
                    host.openFileAt(outlinedFile, symbol.line(), 1, 0);
                }
            });
        }

        void bindState(State state) {
            box.removeAllViews();
            box.setPadding(0, dip(16), 0, dip(16));
            TextView title = new TextView(box.getContext());
            TextView hint = new TextView(box.getContext());
            if (state == State.NO_FILE) {
                title.setText(R.string.tiroir_struct_aucun_fichier);
                hint.setText(R.string.tiroir_struct_aucun_fichier_detail);
            } else if (state == State.UNSUPPORTED) {
                title.setText(R.string.tiroir_struct_langage);
                hint.setText(R.string.tiroir_struct_langage_detail);
            } else {
                title.setText(R.string.tiroir_struct_vide);
                hint.setText("");
            }
            title.setTextSize(13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(getResources().getColor(R.color.sur_var));
            box.addView(title);
            if (hint.length() > 0) {
                hint.setTextSize(12f);
                hint.setTextColor(getResources().getColor(R.color.contour));
                box.addView(hint);
            }
        }
    }

    /** Montage du texte du symbole (visibilité + nom + signature + ligne). */
    private static final class SpannableText {
        static void of(TextView view, Outline.Symbol symbol) {
            android.text.SpannableStringBuilder builder =
                    new android.text.SpannableStringBuilder();
            if (symbol.visibility() != ' ') {
                builder.append(String.valueOf(symbol.visibility())).append(' ');
            }
            builder.append(symbol.name());
            int nameEnd = builder.length();
            if (!symbol.signature().isEmpty()) {
                builder.append(' ').append(symbol.signature());
            }
            builder.append("  ").append(String.valueOf(symbol.line()));
            int lineStart = builder.length() - String.valueOf(symbol.line()).length();
            builder.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                    0, nameEnd, 0);
            builder.setSpan(new android.text.style.ForegroundColorSpan(
                    view.getResources().getColor(R.color.sur)), 0, nameEnd, 0);
            builder.setSpan(new android.text.style.ForegroundColorSpan(
                    view.getResources().getColor(R.color.sur_var)),
                    nameEnd, lineStart, 0);
            builder.setSpan(new android.text.style.ForegroundColorSpan(
                    view.getResources().getColor(R.color.contour)),
                    lineStart, builder.length(), 0);
            view.setText(builder);
            view.setTextSize(12.5f);
        }
    }

    /** Type d'icône d'arborescence équivalent (types Java). */
    private static jo.joide.files.FileKind fileKindOf(Outline.Kind kind) {
        return switch (kind) {
            case INTERFACE -> jo.joide.files.FileKind.JAVA_INTERFACE;
            case ENUM -> jo.joide.files.FileKind.JAVA_ENUM;
            case ANNOTATION_TYPE -> jo.joide.files.FileKind.JAVA_ANNOTATION;
            default -> jo.joide.files.FileKind.JAVA_CLASS;
        };
    }

    /** Monogramme du type de symbole (icônes lettrées, ressources). */
    private int monogram(Outline.Kind kind) {
        return switch (kind) {
            case CLASS, INTERFACE, ENUM, ANNOTATION_TYPE -> 0; // icônes réelles
            case METHOD -> R.string.tiroir_sym_m;
            case FIELD -> R.string.tiroir_sym_f;
            case CONSTRUCTOR -> R.string.tiroir_sym_c;
            case PACKAGE -> R.string.tiroir_sym_p;
            case IMPORTS -> R.string.tiroir_sym_i;
            case ELEMENT -> R.string.tiroir_sym_x;
            case HEADING -> R.string.tiroir_sym_t;
            case KEY -> R.string.tiroir_sym_k;
        };
    }

    private int monogramColor(Outline.Kind kind) {
        return getResources().getColor(kind == Outline.Kind.FIELD
                || kind == Outline.Kind.PACKAGE ? R.color.sur_var : R.color.info);
    }

    private WorkbenchHost host() {
        Fragment parent = requireParentFragment();
        Fragment grandParent = parent != null ? parent.getParentFragment() : null;
        return grandParent instanceof WorkbenchHost host ? host : null;
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (tick != null) {
            main.removeCallbacks(tick);
            tick = null;
        }
        binding = null;
        adapter = null;
    }
}
