package jo.joide.app.drawer;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.coregit.GitService;
import jo.joide.files.FileKind;
import jo.joide.workbench.Diagnostic;

/**
 * Liste aplatie de l'arborescence du tiroir (Lot 3) : RecyclerView +
 * DiffUtil — les rangées se recyclent (le LinearLayout du Lot 2
 * reconstruisait tout), les différences s'animent, les décorations se
 * rebindent seules quand elles changent. Deux sortes d'objets :
 * {@link TreeRow} (entrées) et {@link TreeStateRow} (états honnêtes ;
 * {@code message} porte le texte à afficher — nom du dossier lu, erreur).
 */
final class FileTreeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Événements de rangée remontés au fragment (les données, pas les vues). */
    interface Listener {

        /** Clic simple : ouvrir/replier hors sélection, cocher en sélection. */
        void onRowClick(TreeRow row, View clicked);

        /** Appui long : menu contextuel hors sélection, cocher en sélection. */
        void onRowLongClick(TreeRow row, View clicked);

        /** « Réessayer » d'une rangée d'erreur de listing. */
        void onRetry(String relative);
    }

    private static final int TYPE_ENTRY = 0;
    private static final int TYPE_STATE = 1;
    /** Au-delà, le diff coûte plus qu'il rapporte : rechargement brut. */
    private static final int DIFF_LIMIT = 800;

    private final List<Object> items = new ArrayList<>();
    private final Listener listener;
    private final int indentPx;
    private String highlight = "";

    FileTreeAdapter(Listener listener, int indentPx) {
        this.listener = listener;
        this.indentPx = indentPx;
    }

    /** Remplace la liste (diff animé au-dessous de la garde). */
    void submit(List<Object> next, String highlightQuery) {
        String query = highlightQuery == null ? "" : highlightQuery;
        List<Object> old = new ArrayList<>(items);
        items.clear();
        items.addAll(next);
        highlight = query;
        if (old.size() + next.size() > DIFF_LIMIT * 2) {
            notifyDataSetChanged();
            return;
        }
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
                return key(old.get(oldPos)).equals(key(next.get(newPos)));
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return old.get(oldPos).equals(next.get(newPos));
            }
        }).dispatchUpdatesTo(this);
    }

    private static String key(Object item) {
        if (item instanceof TreeRow row) {
            return "E:" + row.relativePath();
        }
        TreeStateRow state = (TreeStateRow) item;
        return "S:" + state.state() + ":" + state.relativePath();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof TreeRow ? TYPE_ENTRY : TYPE_STATE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ENTRY) {
            return new EntryHolder(inflater.inflate(
                    R.layout.file_tree_row, parent, false));
        }
        return new StateHolder(new LinearLayout(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof EntryHolder entry) {
            entry.bind((TreeRow) item);
        } else {
            ((StateHolder) holder).bind((TreeStateRow) item);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ---- Rangée d'entrée ----------------------------------------------------

    private final class EntryHolder extends RecyclerView.ViewHolder {
        private final IndentGuidesView guides;
        private final CheckBox check;
        private final ImageView chevron;
        private final ImageView icon;
        private final TextView name;
        private final TextView badge;
        private final ImageView gitDot;
        private final ImageView problemDot;
        private TreeRow row;

        EntryHolder(@NonNull View view) {
            super(view);
            guides = view.findViewById(R.id.tree_guides);
            check = view.findViewById(R.id.tree_check);
            chevron = view.findViewById(R.id.tree_chevron);
            icon = view.findViewById(R.id.tree_icon);
            name = view.findViewById(R.id.tree_name);
            badge = view.findViewById(R.id.tree_badge);
            gitDot = view.findViewById(R.id.tree_git_dot);
            problemDot = view.findViewById(R.id.tree_problem_dot);
            view.setOnClickListener(v -> {
                if (row != null) {
                    listener.onRowClick(row, v);
                }
            });
            view.setOnLongClickListener(v -> {
                if (row != null) {
                    listener.onRowLongClick(row, v);
                    return true;
                }
                return false;
            });
        }

        void bind(TreeRow item) {
            row = item;
            Context context = itemView.getContext();
            guides.setDepth(item.depth());
            guides.setHighlightedLevel(item.active() ? item.depth() - 1 : -1);

            if (item.selectionMode()) {
                check.setVisibility(View.VISIBLE);
                check.setChecked(item.selected());
                chevron.setVisibility(View.INVISIBLE);
            } else if (item.directory()) {
                check.setVisibility(View.GONE);
                chevron.setVisibility(View.VISIBLE);
            } else {
                check.setVisibility(View.GONE);
                chevron.setVisibility(View.INVISIBLE);
            }
            chevron.setRotation(item.directory() && item.open() ? 0f : -90f);
            icon.setImageResource(iconRes(item.kind()));
            name.setText(highlighted(item.display()));
            name.setTypeface(item.active()
                    ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            name.setTextColor(nameColor(context, item.git()));
            itemView.setActivated(item.active());
            itemView.setSelected(item.selected());

            if (item.badge() == null) {
                badge.setVisibility(View.GONE);
            } else {
                badge.setVisibility(View.VISIBLE);
                badge.setText(item.badge());
            }
            if (item.git() != null && gitDotRes(item.git()) != 0) {
                gitDot.setVisibility(View.VISIBLE);
                gitDot.setImageResource(gitDotRes(item.git()));
            } else {
                gitDot.setVisibility(View.GONE);
            }
            if (item.problem() == Diagnostic.Severity.ERROR) {
                problemDot.setVisibility(View.VISIBLE);
                problemDot.setImageResource(R.drawable.pastille_diagnostic_erreur);
            } else if (item.problem() == Diagnostic.Severity.WARNING) {
                problemDot.setVisibility(View.VISIBLE);
                problemDot.setImageResource(R.drawable.pastille_diagnostic_avert);
            } else {
                problemDot.setVisibility(View.GONE);
            }
        }
    }

    // ---- Rangée d'état ------------------------------------------------------

    private final class StateHolder extends RecyclerView.ViewHolder {
        private final LinearLayout box;

        StateHolder(@NonNull LinearLayout view) {
            super(view);
            box = view;
            box.setOrientation(LinearLayout.VERTICAL);
        }

        void bind(TreeStateRow state) {
            Context context = box.getContext();
            box.removeAllViews();
            box.setPadding(indentPx / 2 + state.depth() * indentPx + dip(context, 16),
                    dip(context, 8), dip(context, 6), dip(context, 8));
            switch (state.state()) {
                case LOADING -> {
                    LinearLayout line = new LinearLayout(context);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    ProgressBar spinner = new ProgressBar(context);
                    line.addView(spinner, new LinearLayout.LayoutParams(
                            dip(context, 16), dip(context, 16)));
                    TextView reading = new TextView(context);
                    reading.setText(context.getString(
                            R.string.tiroir_lecture, state.message()));
                    reading.setTextSize(12f);
                    reading.setTextColor(context.getResources()
                            .getColor(R.color.sur_var));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.leftMargin = dip(context, 8);
                    line.addView(reading, params);
                    box.addView(line);
                }
                case ERROR -> {
                    TextView message = new TextView(context);
                    message.setText(context.getString(
                            R.string.tiroir_erreur_lecture, state.message()));
                    message.setTextSize(12f);
                    message.setTextColor(context.getResources()
                            .getColor(R.color.sur_var));
                    box.addView(message);
                    box.addView(retryButton(context, state.relativePath()));
                }
                case EMPTY_DIR, EMPTY_PROJECT -> {
                    TextView title = new TextView(context);
                    title.setText(state.state() == TreeState.EMPTY_PROJECT
                            ? R.string.tiroir_projet_vide
                            : R.string.tiroir_dossier_vide);
                    title.setTextSize(13f);
                    title.setTypeface(Typeface.DEFAULT_BOLD);
                    title.setTextColor(context.getResources()
                            .getColor(R.color.sur_var));
                    box.addView(title);
                    TextView hint = new TextView(context);
                    hint.setText(state.state() == TreeState.EMPTY_PROJECT
                            ? R.string.tiroir_projet_vide_conseil
                            : R.string.tiroir_dossier_vide_conseil);
                    hint.setTextSize(12f);
                    hint.setTextColor(context.getResources()
                            .getColor(R.color.contour));
                    box.addView(hint);
                }
                case TRUNCATED -> {
                    TextView truncated = new TextView(context);
                    truncated.setText(context.getString(
                            R.string.tiroir_listing_tronque,
                            Integer.parseInt(state.message())));
                    truncated.setTextSize(11.5f);
                    truncated.setTextColor(context.getResources()
                            .getColor(R.color.contour));
                    box.addView(truncated);
                }
                case FILTER_EMPTY -> {
                    TextView none = new TextView(context);
                    none.setText(R.string.tiroir_filtre_aucun);
                    none.setTextSize(12f);
                    none.setTextColor(context.getResources()
                            .getColor(R.color.sur_var));
                    box.addView(none);
                }
                case ANDROID_EMPTY -> {
                    TextView none = new TextView(context);
                    none.setText(R.string.tiroir_vue_android_vide);
                    none.setTextSize(12f);
                    none.setTextColor(context.getResources()
                            .getColor(R.color.sur_var));
                    box.addView(none);
                }
            }
        }

        /** Bouton « Réessayer » (chip 32dp du studio). */
        private TextView retryButton(Context context, String relative) {
            TextView button = new TextView(context);
            button.setText(R.string.tiroir_reessayer);
            button.setTextSize(12f);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(context.getResources().getColor(R.color.sur));
            button.setBackground(context.getResources()
                    .getDrawable(R.drawable.fond_chip));
            button.setPadding(dip(context, 15), 0, dip(context, 15), 0);
            button.setMinHeight(dip(context, 32));
            button.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(context, 32));
            params.topMargin = dip(context, 6);
            button.setLayoutParams(params);
            button.setOnClickListener(v -> listener.onRetry(relative));
            return button;
        }
    }

    // ---- Aides de rendu -----------------------------------------------------

    /** Nom avec la correspondance du filtre en gras. */
    private CharSequence highlighted(String text) {
        if (highlight.isEmpty()) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String query = highlight.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(query);
        if (index < 0) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        builder.setSpan(new StyleSpan(Typeface.BOLD), index,
                index + highlight.length(), 0);
        return builder;
    }

    private static int dip(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    /** Drawable d'icône par type (aucune extension codée en dur côté UI). */
    static int iconRes(FileKind kind) {
        switch (kind) {
            case FOLDER: return R.drawable.ic_tree_folder;
            case FOLDER_OPEN: return R.drawable.ic_tree_folder_open;
            case SRC_ROOT: return R.drawable.ic_tree_src;
            case TEST_ROOT: return R.drawable.ic_tree_test;
            case PACKAGE: return R.drawable.ic_tree_package;
            case JAVA_CLASS: return R.drawable.ic_tree_java_class;
            case JAVA_ABSTRACT: return R.drawable.ic_tree_java_abstract;
            case JAVA_INTERFACE: return R.drawable.ic_tree_java_interface;
            case JAVA_ENUM: return R.drawable.ic_tree_java_enum;
            case JAVA_ANNOTATION: return R.drawable.ic_tree_java_annotation;
            case KOTLIN: return R.drawable.ic_tree_kotlin;
            case MANIFEST: return R.drawable.ic_tree_manifest;
            case RES_LAYOUT: return R.drawable.ic_tree_res_layout;
            case RES_VALUES: return R.drawable.ic_tree_res_values;
            case RES_DRAWABLE: return R.drawable.ic_tree_res_drawable;
            case XML: return R.drawable.ic_tree_xml;
            case GRADLE: return R.drawable.ic_tree_gradle;
            case GITIGNORE: return R.drawable.ic_tree_gitignore;
            case TEXT: return R.drawable.ic_tree_text;
            case CONFIG: return R.drawable.ic_tree_config;
            case IMAGE: return R.drawable.ic_tree_image;
            case ARCHIVE: return R.drawable.ic_tree_archive;
            case SECRET: return R.drawable.ic_tree_secret;
            default: return R.drawable.ic_tree_unknown;
        }
    }

    /** Point de décoration git (0 = aucune : état absent ou supprimé). */
    static int gitDotRes(GitService.ChangeKind kind) {
        switch (kind) {
            case MODIFIED: return R.drawable.dot_git_modifie;
            case ADDED: return R.drawable.dot_git_ajoute;
            case UNTRACKED: return R.drawable.dot_git_non_suivi;
            default: return 0;
        }
    }

    /** Couleur du nom selon l'état git (maquette : modifié bleu, ajouté ok…). */
    static int nameColor(Context context, GitService.ChangeKind kind) {
        if (kind == null) {
            return context.getResources().getColor(R.color.sur);
        }
        switch (kind) {
            case MODIFIED: return context.getResources().getColor(R.color.git_modifie);
            case ADDED: return context.getResources().getColor(R.color.ok);
            case UNTRACKED: return context.getResources().getColor(R.color.info);
            default: return context.getResources().getColor(R.color.sur);
        }
    }
}
