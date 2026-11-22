package jo.joide.app;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jo.joide.app.R;
import jo.joide.app.databinding.WorkbenchFragmentBinding;
import jo.joide.corestore.ModePreferences;
import jo.joide.app.ModeSetupFragment;
import jo.joide.buildforge.ForgeBackend;
import jo.joide.buildgradle.GradleBackend;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.WorkMode;

/**
 * Atelier (studio HTML, onglet « Éditeur », rendu 1:1) : top bar 52dp (menu,
 * nom, pill de mode, save/undo/redo/play/dots), onglets de fichiers 40dp,
 * fil d'Ariane 34dp avec segment code/aperçu, zone code sc_lowest (brique
 * céleris), dock à poignée (barre 52dp + corps repliable : pills d'état du
 * build, onglets Problèmes·Console·Étapes·Journal, sortie mono), tiroir
 * gauche push 320dp (LeftDrawerFragment : Fichiers · Chercher · Structure ·
 * Git, Lot 0), tiroir droit agent IA.
 *
 * <p>J2 : le build du mode actif alimente la Console et la section Problèmes
 * (diagnostics avec position). Les états restent honnêtes : les commandes
 * portées par les briques non encore câblées se disent, jamais muettes.</p>
 *
 * <p>Lot 0 Assistant/Agent : l'atelier est l'hôte du contexte vivant de
 * l'agent du tiroir droit ({@link jo.joide.app.ai.AgentContext}) — fichier
 * actif (tampon vivant de l'éditeur), diagnostics du dernier build,
 * sélection courante. Des valeurs réelles uniquement.</p>
 *
 * <p>Lot 0 tiroir gauche : le contenu du tiroir vit dans
 * {@link jo.joide.app.drawer.LeftDrawerFragment} (contrat
 * {@link jo.joide.app.drawer.WorkbenchHost}) ; l'atelier garde l'éditeur,
 * le dock et les deux tiroirs (mécanique de poussée, largeurs aux dimens).</p>
 */
public class WorkbenchFragment extends Fragment
        implements jo.joide.app.ai.AgentContext, jo.joide.app.drawer.WorkbenchHost {

    private static final String ARG_PROJECT = "project";

    private WorkbenchFragmentBinding binding;
    private boolean noticeDismissed;
    private String projectPath;

    /** Instance sur un projet (chemin absolu du dossier racine du projet). */
    public static WorkbenchFragment forProject(String projectPath) {
        WorkbenchFragment fragment = new WorkbenchFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    /** Chemin du projet ouvert (null : gabarit de prévisualisation). */
    @Nullable
    protected String projectPath() {
        return getArguments() != null ? getArguments().getString(ARG_PROJECT) : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = WorkbenchFragmentBinding.inflate(inflater, container, false);
        subscribeWorkspaceEvents();
        projectPath = projectPath();

        if (projectPath != null) {
            setupDrawers();
            attachLeftDrawer();
            setupTopBar();
            setupTabs();
            setupDock();
            setupModeVisibility();
            // J2 : construire — le build du mode actif alimente le dock.
            binding.buildButton.setOnClickListener(v -> runBuild());
        }
        return binding.getRoot();
    }

    // ---- Top bar (studio : menu ouvre le tiroir gauche, ⋯ honnête) ----------

    private void setupTopBar() {
        binding.projectTitle.setText(Paths.get(projectPath).getFileName().toString());
        String branch = currentBranch();
        if (branch != null) {
            binding.projectSubtitle.setText(branch);
            binding.projectSubtitle.setVisibility(View.VISIBLE);
        }
        binding.menuButton.setOnClickListener(v ->
                binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START));
        binding.dotsButton.setOnClickListener(this::showOptionsMenu);
        // Preview contenu-principal : enregistrer/annuler/rétablir RÉELS —
        // la brique céleris porte l'historique (undo/redo), l'écriture est
        // nôtre ; l'activation suit les profondeurs, jamais d'état figé.
        binding.saveButton.setOnClickListener(v -> saveActiveFile());
        binding.undoButton.setOnClickListener(v -> {
            if (editor != null && editor.session() != null && editor.session().undo()) {
                onBufferEdited();
            }
        });
        binding.redoButton.setOnClickListener(v -> {
            if (editor != null && editor.session() != null && editor.session().redo()) {
                onBufferEdited();
            }
        });
        // Segments Code / Aperçu (preview : le filaire XML et les images).
        binding.segmentCode.setOnClickListener(v -> showCodeView());
        binding.segmentApercu.setOnClickListener(v -> showPreviewView());
        setupKeyboardFold();
    }

    /** Repli de la barre de symboles quand le clavier monte (preview). */
    private void setupKeyboardFold() {
        final boolean[] symbolWasVisible = {false};
        final int[] rootHeight = {0};
        binding.getRoot().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (binding == null) {
                return;
            }
            android.graphics.Rect frame = new android.graphics.Rect();
            binding.getRoot().getWindowVisibleDisplayFrame(frame);
            if (rootHeight[0] == 0) {
                rootHeight[0] = binding.getRoot().getHeight();
            }
            boolean keyboard = rootHeight[0] - frame.height() > rootHeight[0] / 5;
            if (keyboard && binding.symbolBar.getVisibility() == View.VISIBLE) {
                symbolWasVisible[0] = true;
                binding.symbolBar.setVisibility(View.GONE);
            } else if (!keyboard && symbolWasVisible[0]) {
                symbolWasVisible[0] = false;
                binding.symbolBar.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Thème de l'éditeur aux couleurs de la maquette v2 (fond sc-lowest, encres du studio). */
    private jo.celeris.android.view.EditorTheme editorThemeV2(boolean night) {
        int selection = (requireContext().getColor(R.color.primaire) & 0x00FFFFFF) | 0x40000000;
        android.content.Context context = requireContext();
        int primaire = context.getColor(R.color.primaire);
        int info = context.getColor(R.color.info);
        int ok = context.getColor(R.color.ok);
        int tertiaire = context.getColor(R.color.tertiaire);
        int surVar = context.getColor(R.color.sur_var);
        int contour = context.getColor(R.color.contour);
        int sur = context.getColor(R.color.sur);
        int erreur = context.getColor(R.color.erreur);
        // Preview : coloration aux jetons (ordre SyntaxPalette : KEYWORD,
        // TYPE, STRING, NUMBER, COMMENT, DOC, ANNOTATION, OPERATOR,
        // PUNCTUATION, IDENTIFIER, CONSTANT, brackets ×2, ERROR).
        jo.celeris.android.view.SyntaxPalette palette =
                new jo.celeris.android.view.SyntaxPalette(
                        primaire, info, ok, tertiaire, surVar, surVar,
                        tertiaire, contour, sur, sur, primaire, sur, sur,
                        erreur);
        return new jo.celeris.android.view.EditorTheme(night ? 2 : 1,
                requireContext().getColor(R.color.sc_lowest),
                requireContext().getColor(R.color.sur),
                selection,
                requireContext().getColor(R.color.primaire),
                requireContext().getColor(R.color.contour),
                0x00000000,
                palette,
                night ? jo.celeris.core.syntax.scope.ScopeThemes.dark()
                        : jo.celeris.core.syntax.scope.ScopeThemes.light());
    }

    // ---- Segments Code / Aperçu (preview contenu-principal) ------------------

    /** Retour au Code : l'éditeur céleris reprend la place. */
    private void showCodeView() {
        if (activeFile != null && !activeTextual) {
            return; // binaire : le Code n'a rien à montrer, on reste en Aperçu
        }
        binding.apercuZone.setVisibility(View.GONE);
        binding.etatVide.setVisibility(View.GONE);
        if (editorView != null) {
            editorView.setVisibility(View.VISIBLE);
        }
        styleSegments(false);
    }

    /** Aperçu : filaire XML (tampon vivant) ou message honnête. */
    private void showPreviewView() {
        if (activeFile == null) {
            appendToJournal(getString(R.string.atelier_apercu_indisponible));
            return;
        }
        String name = activeFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml")) {
            String content = editor != null && editor.session() != null
                    && editorView != null && editorView.getVisibility() == View.VISIBLE
                    ? editor.session().text() : null;
            if (content == null) {
                try {
                    content = new String(Files.readAllBytes(activeFile),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    appendToJournal(getString(R.string.atelier_lecture_impossible,
                            activeFile.getFileName().toString()));
                    return;
                }
            }
            renderXmlWireframe(content);
            return;
        }
        // Images : l'état binaire EST l'aperçu (déjà rendu si binaire).
        if (isImageName(name)) {
            try {
                showBinaryState(activeFile, Files.size(activeFile));
            } catch (IOException e) {
                appendToJournal(getString(R.string.atelier_apercu_indisponible));
            }
            return;
        }
        appendToJournal(getString(R.string.atelier_apercu_indisponible));
    }

    private static boolean isImageName(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".webp")
                || name.endsWith(".gif") || name.endsWith(".bmp");
    }

    /**
     * Filaire XML du preview : chaque élément devient une boîte contour avec
     * son tag et son {@code android:id}, imbriquée comme dans le layout.
     * Appui sur une boîte → retour au Code, curseur sur la ligne de l'élément.
     */
    private void renderXmlWireframe(String content) {
        binding.etatVide.setVisibility(View.GONE);
        if (editorView != null) {
            editorView.setVisibility(View.GONE);
        }
        binding.apercuZone.setVisibility(View.VISIBLE);
        binding.apercuZone.removeAllViews();
        try {
            org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setInput(new java.io.StringReader(content));
            LinearLayout root = new LinearLayout(requireContext());
            root.setOrientation(LinearLayout.VERTICAL);
            java.util.Deque<LinearLayout> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            int event = parser.getEventType();
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    LinearLayout box = wireframeBox(parser.getName(),
                            parser.getAttributeValue(
                                    "http://schemas.android.com/apk/res/android", "id"),
                            parser.getLineNumber());
                    LinearLayout parent = stack.peek();
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.topMargin = dip(4);
                    parent.addView(box, params);
                    stack.push(box);
                } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    if (stack.size() > 1) {
                        stack.pop();
                    }
                }
                event = parser.next();
            }
            binding.apercuZone.addView(root);
            styleSegments(true);
        } catch (Exception e) {
            // XML invalide (en cours de frappe) : honnête, pas de filaire figé.
            appendToJournal(getString(R.string.atelier_apercu_indisponible));
            showCodeView();
        }
    }

    /** Une boîte du filaire : bordure contour, libellé tag · @id, saut au clic. */
    private LinearLayout wireframeBox(String tag, String id, int line) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dip(7), dip(5), dip(7), dip(5));
        android.graphics.drawable.GradientDrawable border =
                new android.graphics.drawable.GradientDrawable();
        border.setColor(android.graphics.Color.TRANSPARENT);
        border.setStroke(dip(1), getResources().getColor(R.color.contour));
        border.setCornerRadius(dip(6));
        box.setBackground(border);
        TextView label = new TextView(requireContext());
        String shown = id != null ? tag + " · " + id.replace("@+id/", "@id/")
                .replace("@id/", "") : tag;
        label.setText(shown);
        label.setTextSize(9.5f);
        label.setTextColor(getResources().getColor(R.color.sur_var));
        box.addView(label);
        box.setOnClickListener(v -> {
            showCodeView();
            if (editor != null && editor.session() != null) {
                String text = editor.session().text();
                int offset = offsetOf(text, line, 1);
                editor.session().setSelection(jo.celeris.core.text.MultiSelection.of(
                        new jo.celeris.core.text.SelRange(offset, offset)));
                if (editorView != null) {
                    editorView.post(() -> editorView.requestRectangleOnScreen(
                            editorView.caretBounds()));
                }
            }
        });
        return box;
    }

    /** Style des segments : l'actif sur la pastille primaire (studio). */
    private void styleSegments(boolean previewOn) {
        binding.segmentCode.setBackground(previewOn
                ? null : getResources().getDrawable(R.drawable.fond_segment_actif));
        binding.segmentCode.setColorFilter(previewOn
                ? getResources().getColor(R.color.sur_var)
                : getResources().getColor(R.color.sur_conteneur_primaire));
        binding.segmentApercu.setBackground(previewOn
                ? getResources().getDrawable(R.drawable.fond_segment_actif) : null);
        binding.segmentApercu.setColorFilter(previewOn
                ? getResources().getColor(R.color.sur_conteneur_primaire)
                : getResources().getColor(R.color.sur_var));
    }

    /** Bascule la barre de symboles sous l'éditeur (v2 : via le menu ⋯ ou le ⋯ du dock). */
    private void toggleSymbolBar() {
        binding.symbolBar.setVisibility(binding.symbolBar.getVisibility()
                == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (binding.symbolBar.getChildCount() == 0) {
            buildSymbolBar();
        }
    }

    /** Branche courante lue dans .git/HEAD ; null si le projet n'est pas un dépôt. */
    private String currentBranch() {
        try {
            Path head = Paths.get(projectPath, ".git", "HEAD");
            if (!java.nio.file.Files.isRegularFile(head)) {
                return null;
            }
            String line = new String(java.nio.file.Files.readAllBytes(head),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            String prefix = "ref: refs/heads/";
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
            return line.length() >= 7 ? line.substring(0, 7) : line;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Ouvre le tiroir gauche : LeftDrawerFragment (fragment enfant créé à la
     *  première ouverture, conservé ensuite — l'état survit aux fermetures). */
    private void attachLeftDrawer() {
        if (getChildFragmentManager().findFragmentById(R.id.left_drawer_container) == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.left_drawer_container,
                            jo.joide.app.drawer.LeftDrawerFragment.forProject(projectPath))
                    .commit();
        }
    }

    // ---- Lot 8 : les mutations de l'agent rafraîchissent l'éditeur ------------

    private final java.util.concurrent.atomic.AtomicBoolean eventsSubscribed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** S'abonne au bus une fois : fichier changé sur le disque → recharger l'onglet ouvert. */
    private void subscribeWorkspaceEvents() {
        if (!eventsSubscribed.compareAndSet(false, true)) {
            return;
        }
        workspaceEvents.subscribe(event -> {
            java.nio.file.Path changed = null;
            java.nio.file.Path renamedFrom = null;
            if (event instanceof jo.joide.workbench.WorkspaceEvents.FileChangedOnDisk changedEvent) {
                changed = changedEvent.file();
            } else if (event instanceof jo.joide.workbench.WorkspaceEvents.FileCreated created) {
                changed = created.file();
            } else if (event instanceof jo.joide.workbench.WorkspaceEvents.FileRenamed renamed) {
                changed = renamed.to();
                renamedFrom = renamed.from();
            }
            if (changed == null || activeFile == null) {
                return;
            }
            java.nio.file.Path finalChanged = changed;
            java.nio.file.Path finalRenamedFrom = renamedFrom;
            requireActivity().runOnUiThread(() -> {
                // L'onglet actif concerné se relit depuis le disque : l'agent
                // écrit, l'atelier suit — sans action de l'utilisateur.
                if (activeFile == null) {
                    return;
                }
                // Lot 3 tiroir : un renommage depuis l'arbre déplace le fichier
                // actif (l'onglet suit son nouveau chemin, jamais un chemin mort).
                if (finalRenamedFrom != null && finalRenamedFrom.equals(activeFile)) {
                    activeFile = finalChanged;
                    openFile(finalChanged);
                    return;
                }
                if (finalChanged.equals(skipNextDiskReload)) {
                    // Notre propre sauvegarde : le tampon est déjà à jour,
                    // pas de relecture (le curseur reste où il est).
                    skipNextDiskReload = null;
                    return;
                }
                if (activeFile.equals(finalChanged)) {
                    openFile(finalChanged);
                }
            });
        });
    }

    // ---- Contrat WorkbenchHost (le tiroir gauche ne touche jamais l'éditeur) --

    @Override
    public void openFile(Path file) {
        // Le tampon quitté vit en cache : revenir retrouve les modifications.
        snapshotCurrentBuffer();
        activeFile = file;
        if (!openTabs.contains(file)) {
            openTabs.add(file);
        }
        String cached = modifiedBuffers.get(file);
        if (cached != null) {
            attachEditor(file.getFileName().toString(), cached);
            currentDirty = true;
        } else {
            openFromDisk(file);
        }
        rebuildTabs();
        refreshFileChrome();
        checkSafetyCopy(file);
    }

    /** Lecture disque avec états honnêtes : binaire, volumineux, texte. */
    private void openFromDisk(Path file) {
        currentDirty = false;
        activeTextual = true;
        try {
            long size = Files.size(file);
            if (size == 0) {
                // Fichier vide : éditable tel quel (le panneau vide ne concerne
                // que l'absence de fichier).
                attachEditor(file.getFileName().toString(), "");
                return;
            }
            if (isBinary(file)) {
                showBinaryState(file, size);
                return;
            }
            if (size > 256 * 1024) {
                byte[] head = new byte[256 * 1024];
                try (var in = Files.newInputStream(file)) {
                    int read = in.read(head);
                    if (read > 0 && read < head.length) {
                        head = java.util.Arrays.copyOf(head, read);
                    }
                }
                attachEditor(file.getFileName().toString(),
                        new String(head, StandardCharsets.UTF_8));
                appendToJournal(getString(R.string.atelier_volumineux,
                        humanSize(size)));
                return;
            }
            attachEditor(file.getFileName().toString(),
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            appendToJournal(getString(R.string.atelier_lecture_impossible,
                    file.getFileName().toString()));
        }
    }

    /** Binaire = octet nul dans la tête (aperçu image réel si image). */
    private boolean isBinary(Path file) throws IOException {
        byte[] head = new byte[8192];
        int read;
        try (var in = Files.newInputStream(file)) {
            read = in.read(head);
        }
        for (int i = 0; i < Math.max(0, read); i++) {
            if (head[i] == 0) {
                return true;
            }
        }
        return read < 0; // vide côté flux : traité comme texte vide ailleurs
    }

    /** État binaire du preview : type + taille ; l'image se rend, réelle. */
    private void showBinaryState(Path file, long size) {
        activeTextual = false;
        String name = file.getFileName().toString();
        String ext = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT)
                : "?";
        binding.etatVide.setVisibility(View.GONE);
        if (editorView != null) {
            editorView.setVisibility(View.GONE);
        }
        binding.apercuZone.setVisibility(View.VISIBLE);
        binding.apercuZone.removeAllViews();
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".bmp")) {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.toString(), bounds);
            int sample = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 1024);
            android.graphics.BitmapFactory.Options options =
                    new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            android.graphics.Bitmap bitmap =
                    android.graphics.BitmapFactory.decodeFile(file.toString(), options);
            if (bitmap != null) {
                ImageView image = new ImageView(requireContext());
                image.setImageBitmap(bitmap);
                image.setMaxWidth(dip(280));
                image.setAdjustViewBounds(true);
                card.addView(image);
                TextView caption = new TextView(requireContext());
                caption.setText(getString(R.string.atelier_image_taille,
                        humanSize(size), bounds.outWidth, bounds.outHeight));
                caption.setTextSize(11f);
                caption.setTextColor(getResources().getColor(R.color.sur_var));
                caption.setPadding(0, dip(8), 0, 0);
                card.addView(caption);
                binding.apercuZone.addView(card);
                styleSegments(true);
                return;
            }
        }
        TextView title = new TextView(requireContext());
        title.setText(R.string.atelier_binaire_titre);
        title.setTextSize(14f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(getResources().getColor(R.color.sur_var));
        title.setPadding(0, dip(20), 0, 0);
        card.addView(title);
        TextView detail = new TextView(requireContext());
        detail.setText(getString(R.string.atelier_binaire_detail, ext, humanSize(size)));
        detail.setTextSize(12f);
        detail.setTextColor(getResources().getColor(R.color.contour));
        detail.setPadding(0, dip(4), 0, 0);
        card.addView(detail);
        binding.apercuZone.addView(card);
        styleSegments(true);
    }

    /** « 4,2 Kio » — taille lisible (jamais d'octets bruts en UI). */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " o";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f Kio", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f Mio", bytes / 1048576.0);
    }

    /** Fil d'Ariane + sous-titre (branche · fichier actif) à chaque ouverture. */
    private void refreshFileChrome() {
        binding.breadcrumb.setText(relativize(
                activeFile != null ? activeFile : Paths.get(projectPath))
                .replace('/', '›') + getString(R.string.atelier_fil_point));
        binding.projectSubtitle.setText(subtitleBranch != null && activeFile != null
                ? subtitleBranch + " · " + activeFile.getFileName()
                : subtitleBranch != null ? subtitleBranch
                : activeFile != null ? activeFile.getFileName().toString() : "");
        binding.projectSubtitle.setVisibility(
                binding.projectSubtitle.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    @Nullable
    @Override
    public Path activeFile() {
        return activeFile;
    }

    @Override
    public void openFileAt(Path file, int line, int column, int length) {
        if (!file.equals(activeFile)) {
            openFile(file);
        }
        // Sélection de l'occurrence (Lot 4 tiroir : résultats de recherche).
        // La brique éditeur suit la sélection posée sur la session.
        if (editor == null || editor.session() == null) {
            return;
        }
        String text = editor.session().text();
        int offset = offsetOf(text, line, column);
        int end = Math.min(text.length(),
                Math.max(offset, offset + Math.max(0, length)));
        editor.session().setSelection(jo.celeris.core.text.MultiSelection.of(
                new jo.celeris.core.text.SelRange(offset, end)));
    }

    /** Décalage absolu d'une position 1-based (borné au texte). */
    private static int offsetOf(String text, int line, int column) {
        int offset = 0;
        int current = 1;
        while (current < line) {
            int breakIndex = text.indexOf('\n', offset);
            if (breakIndex < 0) {
                return text.length();
            }
            offset = breakIndex + 1;
            current++;
        }
        return Math.min(text.length(),
                offset + Math.max(0, column - 1));
    }

    @NonNull
    @Override
    public List<Path> recentFiles() {
        // Onglets ouverts, l'actif d'abord (classement « aller au fichier »).
        List<Path> recents = new ArrayList<>(openTabs);
        if (activeFile != null && recents.remove(activeFile)) {
            recents.add(0, activeFile);
        }
        return recents;
    }

    @Nullable
    @Override
    public String activeFileText() {
        // Tampon vivant (modifications non enregistrées comprises).
        return editor != null && editor.session() != null
                ? editor.session().text() : null;
    }

    @Override
    public int cursorOffset() {
        if (editor == null || editor.session() == null) {
            return -1;
        }
        List<Integer> carets = editor.session().selection().carets();
        return carets.isEmpty() ? -1 : carets.get(carets.size() - 1);
    }

    @NonNull
    @Override
    public List<jo.joide.workbench.Diagnostic> diagnostics() {
        return new ArrayList<>(lastStructuredDiagnostics);
    }

    @Override
    public void closeLeftDrawer() {
        binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
    }

    @Override
    public void openGitScreen() {
        // Comportement conservé du Lot 0 : l'écran Git plein écran (le client
        // Git dans le tiroir arrive au lot Git).
        binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
        jo.joide.app.git.GitFragment git = new jo.joide.app.git.GitFragment();
        Bundle args = new Bundle();
        args.putString("project", projectPath);
        git.setArguments(args);
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(), git)
                .addToBackStack(null)
                .commit();
    }

    // ---- Contexte vivant de l'agent (Lot 0 : valeurs réelles seulement) ----

    // ---- Tiroirs (gauche : fichiers · droite : agent IA) — ils POUSSENT le contenu --

    private void setupDrawers() {
        androidx.drawerlayout.widget.DrawerLayout drawers = binding.drawerLayout;
        // Pas de voile : le contenu est poussé, pas recouvert.
        drawers.setScrimColor(android.graphics.Color.TRANSPARENT);
        drawers.setDrawerElevation(0f);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // Largeurs par dimensions (Lot 0 tiroir : plus de valeur en dur — le
        // 320dp du layout et de la doc fait foi, borné par l'écran − 56dp).
        ViewGroup.LayoutParams right = binding.rightDrawer.getLayoutParams();
        right.width = Math.min(
                (int) getResources().getDimension(R.dimen.drawer_droit_l),
                screenWidth - dip(56));
        binding.rightDrawer.setLayoutParams(right);
        ViewGroup.LayoutParams left = binding.leftDrawerPanel.getLayoutParams();
        left.width = Math.min(
                (int) getResources().getDimension(R.dimen.drawer_gauche_l),
                screenWidth - dip(56));
        binding.leftDrawerPanel.setLayoutParams(left);

        androidx.activity.OnBackPressedCallback closeOnBack =
                new androidx.activity.OnBackPressedCallback(false) {
                    @Override
                    public void handleOnBackPressed() {
                        drawers.closeDrawers();
                    }
                };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), closeOnBack);

        drawers.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout
                .SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                // Gauche : le contenu glisse vers la droite ; droite : vers la gauche.
                float sign = drawerView == binding.rightDrawer ? -1f : 1f;
                binding.mainContent.setTranslationX(
                        sign * drawerView.getWidth() * slideOffset);
            }

            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                closeOnBack.setEnabled(true);
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                closeOnBack.setEnabled(
                        drawers.isDrawerOpen(androidx.core.view.GravityCompat.START)
                                || drawers.isDrawerOpen(androidx.core.view.GravityCompat.END));
            }
        });
    }

    // ---- Bus de l'espace de travail (Lot 8 : un seul chemin de mise à jour) ----

    private final jo.joide.workbench.WorkspaceEvents workspaceEvents =
            new jo.joide.workbench.WorkspaceEvents();

    /** Bus partagé : arbre, recherche, structure, Git, agent IA (mêmes événements). */
    public jo.joide.workbench.WorkspaceEvents workspaceEvents() {
        return workspaceEvents;
    }

    /** Demande d'explication en attente pour l'agent (posée hors de l'atelier). */
    private static volatile String pendingAgentAsk;

    /**
     * Ouvre le tiroir droit de l'agent avec une question préremplie
     * ( « Expliquer cette erreur » depuis les Problèmes — Lot 8).
     */
    public void openAgentPanel(String prompt) {
        openAiDrawer();
        getChildFragmentManager().executePendingTransactions();
        android.view.View drawer = binding.drawerLayout;
        drawer.post(() -> {
            androidx.fragment.app.Fragment panel = getChildFragmentManager()
                    .findFragmentById(R.id.ai_drawer_container);
            if (panel instanceof jo.joide.app.ai.AiPanelFragment agentPanel) {
                agentPanel.prefillPrompt(prompt);
            }
        });
    }

    /** Inscrit une demande d'explication (consommée à la reprise de l'atelier). */
    public static void requestAgentExplanation(String prompt) {
        pendingAgentAsk = prompt;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingAgentAsk != null) {
            String ask = pendingAgentAsk;
            pendingAgentAsk = null;
            openAgentPanel(ask);
        }
    }

    /** Ouvre le tiroir droit : agent IA (fragment enfant créé à la première ouverture). */
    private void openAiDrawer() {
        if (getChildFragmentManager().findFragmentById(R.id.ai_drawer_container) == null) {
            jo.joide.app.ai.AiPanelFragment panel =
                    jo.joide.app.ai.AiPanelFragment.forProject(projectPath);
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.ai_drawer_container, panel)
                    .commit();
        }
        binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.END);
    }

    /** Menu d'options de la barre (⋯) : actions réelles de l'atelier. */
    private void showOptionsMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.workbench_options);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.option_fichiers) {
                binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
            } else if (id == R.id.option_agent_ia) {
                openAiDrawer();
            } else if (id == R.id.option_construire) {
                binding.buildButton.performClick();
            } else if (id == R.id.option_terminal) {
                binding.terminalButton.performClick();
            } else if (id == R.id.option_recherche) {
                binding.dockSearch.performClick();
            } else if (id == R.id.option_console) {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_HALF_EXPANDED);
            } else if (id == R.id.option_symboles) {
                toggleSymbolBar();
            } else if (id == R.id.option_mode) {
                binding.modeChip.performClick();
            } else if (id == R.id.option_refaire) {
                binding.redoButton.performClick();
            }
            return true;
        });
        menu.show();
    }

    // ---- Onglets de fichiers (studio : 32dp sc2, point alerte, ×) ----------

    private final List<Path> openTabs = new ArrayList<>();

    /** Fichier actif de l'éditeur (contexte vivant de l'agent — Lot 0). */
    private Path activeFile;

    /** Tampons modifiés par fichier (preview contenu-principal : le ✱ suit
     *  le tampon réel — changer d'onglet ne perd plus les modifications). */
    private final Map<Path, String> modifiedBuffers = new HashMap<>();

    /** Vrai si le tampon actif a des modifications non enregistrées. */
    private boolean currentDirty;

    /** Plan du fichier courant (chips de la barre de symboles). */
    private final List<jo.celeris.core.syntax.structure.OutlineEntry> currentPlan =
            new ArrayList<>();

    /** Vue céleris (bascule Code/Aperçu, repli clavier). */
    private jo.celeris.android.view.EditorView editorView;

    /** Branche du sous-titre (complétée du fichier actif à chaque ouverture). */
    private String subtitleBranch;

    /** Rangée du chemin de symboles (preview : au-dessus des touches). */
    private LinearLayout symbolPathRow;

    /** Fichier dont le prochain événement disque est notre sauvegarde. */
    private Path skipNextDiskReload;

    /** Dernière session câblée (les écoutes suivent les ouvertures). */
    private jo.celeris.core.text.TextSession wiredSession;

    /** Vrai si le fichier actif est textuel (le Code n'ouvre pas un binaire). */
    private boolean activeTextual = true;

    /** Diagnostics du dernier build (« fichier:ligne: message » — Lot 0). */
    private final List<String> lastDiagnostics = new ArrayList<>();

    /** Diagnostics structurés du dernier build (Lot 2 tiroir : décorations
     *  d'arborescence — pastille erreur/avertissement par fichier). */
    private final List<jo.joide.workbench.Diagnostic> lastStructuredDiagnostics =
            new ArrayList<>();

    private void setupTabs() {
        // Premier fichier ouvert d'emblée (studio : MainActivity.java actif).
        // La découverte ne tourne qu'ici : reconstruire la rangée (fermeture,
        // ✱) ne ré-ajoute jamais un onglet fermé.
        try (var stream = Files.walk(Paths.get(projectPath))) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .limit(3)
                    .forEach(openTabs::add);
        } catch (IOException e) {
            appendToJournal(getString(R.string.atelier_lecture_impossible,
                    e.getMessage() != null ? e.getMessage() : ""));
        }
        if (openTabs.isEmpty()) {
            try (var stream = Files.walk(Paths.get(projectPath))) {
                stream.filter(Files::isRegularFile).limit(3).forEach(openTabs::add);
            } catch (IOException e) {
                // arborescence vide : onglets vides, journal honnête déjà écrit.
            }
        }
        rebuildTabs();
        if (!openTabs.isEmpty()) {
            openFile(openTabs.get(0));
        } else {
            showEmptyState();
        }
    }

    /** Reconstruit la rangée d'onglets : ✱ réel par tampon, ＋, état vide. */
    private void rebuildTabs() {
        binding.filesTabs.removeAllViews();
        for (Path file : openTabs) {
            binding.filesTabs.addView(fileTab(file, file.equals(activeFile)));
        }
        TextView plus = new TextView(requireContext());
        plus.setText("＋");
        plus.setTextSize(15f);
        plus.setGravity(android.view.Gravity.CENTER);
        plus.setTextColor(getResources().getColor(R.color.sur_var));
        plus.setPadding(dip(12), 0, dip(8), 0);
        plus.setOnClickListener(v -> askNewFile());
        binding.filesTabs.addView(plus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(32)));
    }

    /** ＋ : crée un vrai fichier à la racine du projet et l'ouvre. */
    private void askNewFile() {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(R.string.atelier_onglet_nom_indice);
        input.setTextSize(13f);
        input.setPadding(dip(20), dip(8), dip(20), 0);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.atelier_onglet_nouveau)
                .setView(input)
                .setPositiveButton(R.string.atelier_onglet_nouveau,
                        (dialog, which) -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty() || name.contains("/")
                                    || name.contains("\\")) {
                                toast(getString(R.string.atelier_onglet_nom_invalide));
                                return;
                            }
                            Path created = Paths.get(projectPath).resolve(name);
                            if (Files.exists(created)) {
                                toast(getString(R.string.atelier_onglet_nom_invalide));
                                return;
                            }
                            try {
                                Files.write(created, new byte[0]);
                            } catch (IOException e) {
                                appendToJournal(getString(
                                        R.string.atelier_lecture_impossible,
                                        e.getMessage() != null ? e.getMessage() : ""));
                                return;
                            }
                            appendToJournal(getString(
                                    R.string.atelier_onglet_cree_ok, name));
                            openFile(created);
                        })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    /** État honnête sans fichier : la zone dit quoi faire, jamais muette. */
    private void showEmptyState() {
        if (editorView != null) {
            editorView.setVisibility(View.GONE);
        }
        binding.apercuZone.setVisibility(View.GONE);
        binding.etatVide.setVisibility(View.VISIBLE);
        binding.breadcrumb.setText(relativize(Paths.get(projectPath))
                .replace('/', '›') + getString(R.string.atelier_fil_point));
    }

    /** Un onglet : icône fichier + nom + point d'alerte (1er) + fermeture. */
    private View fileTab(Path file, boolean active) {
        LinearLayout tab = new LinearLayout(requireContext());
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tab.setPadding(dip(12), 0, dip(6), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(32));
        params.rightMargin = dip(6);
        tab.setLayoutParams(params);
        tab.setBackground(active
                ? getResources().getDrawable(R.drawable.fond_onglet_fichier)
                : getResources().getDrawable(R.drawable.fond_onglet_inactif));

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_file);
        icon.setColorFilter(getResources().getColor(
                active ? R.color.sur_sc2 : R.color.sur_var));
        tab.addView(icon, new LinearLayout.LayoutParams(dip(15), dip(15)));

        TextView name = new TextView(requireContext());
        name.setText(file.getFileName().toString());
        name.setTextSize(12.5f);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(getResources().getColor(
                active ? R.color.sur_sc2 : R.color.sur_var));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.leftMargin = dip(7);
        name.setLayoutParams(nameParams);
        tab.addView(name);

        // ✱ du preview : suit le tampon RÉEL (écoute d'édition céleris).
        if (modifiedBuffers.containsKey(file)
                || (file.equals(activeFile) && currentDirty)) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dip(7), dip(7));
            dotParams.leftMargin = dip(7);
            dot.setLayoutParams(dotParams);
            dot.setBackground(getResources().getDrawable(R.drawable.fond_point_alerte));
            tab.addView(dot);
        }

        ImageView close = new ImageView(requireContext());
        close.setImageResource(R.drawable.ic_x);
        close.setColorFilter(getResources().getColor(
                active ? R.color.sur_sc2 : R.color.sur_var));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dip(20), dip(20));
        closeParams.leftMargin = dip(4);
        close.setLayoutParams(closeParams);
        close.setPadding(dip(4), dip(4), dip(4), dip(4));
        close.setOnClickListener(v -> closeTab(file));
        tab.addView(close);
        tab.setOnClickListener(v -> openFile(file));
        // Appui long (preview) : Fermer · Fermer les autres · Copier le chemin.
        tab.setOnLongClickListener(v -> {
            showTabMenu(file, tab);
            return true;
        });
        return tab;
    }

    /** Menu d'onglet (preview : appui long). */
    private void showTabMenu(Path file, View anchor) {
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.atelier_onglet_fermer);
        menu.getMenu().add(0, 2, 1, R.string.atelier_onglet_fermer_autres);
        menu.getMenu().add(0, 3, 2, R.string.atelier_onglet_copier_chemin);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                closeTab(file);
            } else if (item.getItemId() == 2) {
                closeOtherTabs(file);
            } else if (item.getItemId() == 3) {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        getString(R.string.atelier_onglet_copier_chemin),
                        file.toString()));
                toast(getString(R.string.atelier_chemin_copie));
            }
            return true;
        });
        menu.show();
    }

    /** Fermeture d'onglet : confirmation si le tampon est modifié (preview). */
    private void closeTab(Path file) {
        boolean modified = modifiedBuffers.containsKey(file)
                || (file.equals(activeFile) && currentDirty);
        if (!modified) {
            removeTab(file);
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.atelier_onglet_modifie_dialogue,
                        file.getFileName().toString()))
                .setPositiveButton(R.string.atelier_onglet_enregistrer,
                        (dialog, which) -> {
                            if (file.equals(activeFile)) {
                                saveActiveFile();
                            } else {
                                saveBuffer(file, modifiedBuffers.get(file));
                            }
                            removeTab(file);
                        })
                .setNegativeButton(R.string.atelier_onglet_ignorer,
                        (dialog, which) -> removeTab(file))
                .setNeutralButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    /** Ferme tous les onglets sauf un ; jamais de perte silencieuse. */
    private void closeOtherTabs(Path keep) {
        long modifiedOthers = openTabs.stream()
                .filter(f -> !f.equals(keep))
                .filter(f -> modifiedBuffers.containsKey(f)
                        || (f.equals(activeFile) && currentDirty))
                .count();
        if (modifiedOthers == 0) {
            for (Path file : new ArrayList<>(openTabs)) {
                if (!file.equals(keep)) {
                    removeTab(file);
                }
            }
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.atelier_onglet_autres_modifiees,
                        (int) modifiedOthers))
                .setPositiveButton(R.string.atelier_onglet_fermer_autres,
                        (dialog, which) -> {
                            for (Path file : new ArrayList<>(openTabs)) {
                                if (!file.equals(keep)) {
                                    removeTab(file);
                                }
                            }
                        })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    /** Retire un onglet (tampon jeté, sauvegarde de sûreté effacée). */
    private void removeTab(Path file) {
        modifiedBuffers.remove(file);
        deleteSafetyCopy(file);
        openTabs.remove(file);
        if (file.equals(activeFile)) {
            activeFile = null;
            currentDirty = false;
            if (!openTabs.isEmpty()) {
                openFile(openTabs.get(0));
            } else {
                showEmptyState();
            }
        }
        rebuildTabs();
    }

    // ---- Tiroir gauche : contenu extrait (Lot 0) → LeftDrawerFragment -------

    // ---- Contexte vivant de l'agent (Lot 0 : valeurs réelles seulement) ----

    @Nullable
    @Override
    public String agentActiveFileName() {
        return activeFile != null ? activeFile.getFileName().toString() : null;
    }

    @Nullable
    @Override
    public String agentActiveFilePath() {
        return activeFile != null ? relativize(activeFile) : null;
    }

    @Nullable
    @Override
    public String agentActiveFileContent() {
        // Tampon vivant de l'éditeur (modifications non enregistrées comprises),
        // pas une relecture disque — le fichier peut être en cours d'édition.
        return editor != null && editor.session() != null
                ? editor.session().text() : null;
    }

    @NonNull
    @Override
    public List<String> agentProblemLines() {
        return new ArrayList<>(lastDiagnostics);
    }

    @Nullable
    @Override
    public String agentBranchName() {
        return currentBranch();
    }

    @Nullable
    @Override
    public String agentSelectionText() {
        if (editor == null || editor.session() == null) {
            return null;
        }
        jo.celeris.core.text.TextSession session = editor.session();
        String text = session.text();
        StringBuilder selection = new StringBuilder();
        for (jo.celeris.core.text.SelRange range : session.selection().ranges()) {
            if (range.isEmpty()) {
                continue;
            }
            int start = Math.max(0, Math.min(range.start(), text.length()));
            int end = Math.max(start, Math.min(range.end(), text.length()));
            if (selection.length() > 0) {
                selection.append('\n');
            }
            selection.append(text, start, end);
            if (selection.length() > 2_000) {
                break; // garde-fou : une sélection bornée suffit au contexte
            }
        }
        return selection.length() == 0 ? null : selection.toString();
    }

    /** Le tampon actif part en cache s'il est modifié (changement d'onglet). */
    private void snapshotCurrentBuffer() {
        if (activeFile != null && currentDirty
                && editor != null && editor.session() != null) {
            modifiedBuffers.put(activeFile, editor.session().text());
        }
    }

    /** Enregistre le tampon actif sur disque (événement disque publié). */
    private void saveActiveFile() {
        if (activeFile == null) {
            return;
        }
        if (!activeTextual || editor == null || editor.session() == null
                || editorView == null || editorView.getVisibility() != View.VISIBLE) {
            return; // état binaire/aperçu : rien à enregistrer.
        }
        saveBuffer(activeFile, editor.session().text());
    }

    /** Écrit un tampon (actif ou en cache) : jamais muet, jamais d'écrasement
     *  silencieux d'erreur — le journal dit ce qui s'est passé. */
    private void saveBuffer(Path file, String content) {
        try {
            Files.write(file, (content == null ? "" : content)
                    .getBytes(StandardCharsets.UTF_8));
            modifiedBuffers.remove(file);
            if (file.equals(activeFile)) {
                currentDirty = false;
            }
            deleteSafetyCopy(file);
            appendToJournal(getString(R.string.atelier_enregistre_ok,
                    file.getFileName().toString()));
            skipNextDiskReload = file;
            workspaceEvents.publish(
                    new jo.joide.workbench.WorkspaceEvents.FileChangedOnDisk(file));
            rebuildTabs();
            refreshTools();
        } catch (IOException e) {
            appendToJournal(getString(R.string.atelier_lecture_impossible,
                    e.getMessage() != null ? e.getMessage() : ""));
        }
    }

    /** Sauvegarde de sûreté (cache, jamais dans le projet) : suspension. */
    private Path safetyCopyFor(Path file) {
        String rel = Paths.get(projectPath).relativize(file).toString();
        return requireContext().getCacheDir().toPath()
                .resolve("sauvegardes").resolve(rel);
    }

    private void deleteSafetyCopy(Path file) {
        try {
            Files.deleteIfExists(safetyCopyFor(file));
        } catch (IOException ignored) {
            // le cache est jetable : une copie restante ne gêne personne
        }
    }

    /** À la suspension : les tampons modifiés partent au cache. */
    @Override
    public void onPause() {
        super.onPause();
        snapshotCurrentBuffer();
        if (projectPath == null) {
            return;
        }
        for (Map.Entry<Path, String> entry : modifiedBuffers.entrySet()) {
            try {
                Path target = safetyCopyFor(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // mieux vaut une suspension silencieuse qu'un crash bloquant
            }
        }
    }

    /** Une sauvegarde de sûreté plus récente que le disque → Restaurer. */
    private void checkSafetyCopy(Path file) {
        Path safety = safetyCopyFor(file);
        if (!Files.isRegularFile(safety)) {
            return;
        }
        try {
            if (Files.getLastModifiedTime(safety).toMillis()
                    <= Files.getLastModifiedTime(file).toMillis()) {
                return;
            }
            String saved = new String(Files.readAllBytes(safety),
                        StandardCharsets.UTF_8);
            String disk = editor != null && editor.session() != null
                    && editorView != null && editorView.getVisibility() == View.VISIBLE
                    ? editor.session().text() : null;
            if (disk != null && saved.equals(disk)) {
                return;
            }
            com.google.android.material.snackbar.Snackbar
                    .make(binding.getRoot(),
                            R.string.atelier_sauvegarde_proposee,
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction(R.string.atelier_restaure, v -> {
                        attachEditor(file.getFileName().toString(), saved);
                        currentDirty = true;
                        rebuildTabs();
                        appendToJournal(getString(
                                R.string.atelier_sauvegarde_restauree));
                    })
                    .show();
        } catch (IOException ignored) {
            // cache illisible : le disque fait foi
        }
    }

    /** Activation honnête des outils (annuler/rétablir : profondeurs céleris). */
    private void refreshTools() {
        boolean canUndo = false;
        boolean canRedo = false;
        if (editor != null && editor.session() != null) {
            canUndo = editor.session().undoDepth() > 0;
            canRedo = editor.session().redoDepth() > 0;
        }
        binding.undoButton.setEnabled(canUndo);
        binding.undoButton.setAlpha(canUndo ? 1f : 0.35f);
        binding.redoButton.setEnabled(canRedo);
        binding.redoButton.setAlpha(canRedo ? 1f : 0.35f);
        boolean canSave = activeFile != null && (currentDirty
                || modifiedBuffers.containsKey(activeFile));
        binding.saveButton.setEnabled(canSave);
        binding.saveButton.setAlpha(canSave ? 1f : 0.35f);
    }

    /** Une édition a eu lieu : ✱ et outils suivent (preview). */
    private void onBufferEdited() {
        if (binding == null) {
            return;
        }
        currentDirty = true;
        rebuildTabs();
        refreshTools();
    }

    // ---- Éditeur (brique céleris, conception §6) ----------------------------

    private jo.celeris.android.host.CelerisEditor editor;

    /**
     * Monte l'éditeur céleris dans la zone d'édition : vue + couche de popups +
     * kit de langages (32 langages embarqués, grammaires packagées —
     * {@code builtinPacks}). La barre de symboles (OutlineView) est la brique
     * android-widgets ; le clavier délégué (KeyboardPopup) suivra au lot terminal.
     */
    private void attachEditor(String fileName, String content) {
        Context context = requireContext();
        if (editor == null) {
            jo.celeris.android.view.EditorView view =
                    new jo.celeris.android.view.EditorView(context);
            jo.celeris.android.widgets.PopupLayer popups =
                    new jo.celeris.android.widgets.PopupLayer(context);
            binding.editorArea.addView(view,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            binding.editorArea.addView(popups,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            editorView = view;
            // Plan du fichier (chips de la barre de symboles — même moteur
            // que la structure : la brique céleris produit les entrées).
            view.addPlanListener(plan -> {
                currentPlan.clear();
                if (plan != null) {
                    currentPlan.addAll(plan);
                }
                updateSymbolPath();
            });
            jo.celeris.core.language.LanguageKit kit =
                    jo.celeris.core.language.LanguageKit.builder(
                            jo.celeris.core.language.LanguageKit.sharedDaemon())
                            .grammarFactory(jo.celeris.core.syntax.grammars.PackGrammars.INSTANCE)
                            .builtinPacks()
                            .build();
            editor = jo.celeris.android.host.CelerisEditor.attach(view, popups, kit);
            boolean night = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            view.settings().setTheme(editorThemeV2(night));
            view.settings().setFontSizeSp(12.5f);
        }
        editor.open(fileName, content);
        wireSession();
        // Un fichier texte s'ouvre : Code visible, états masqués (preview).
        showCodeView();
        refreshTools();
    }

    /** Écoutes du tampon courant : édition (✱), curseur (chemin de symboles). */
    private void wireSession() {
        if (editor == null || editor.session() == null) {
            return;
        }
        jo.celeris.core.text.TextSession session = editor.session();
        if (session == wiredSession) {
            return;
        }
        if (wiredSession != null) {
            wiredSession.removeEditListener(sessionEdits);
            wiredSession.removeSelectionListener(sessionSelection);
        }
        session.addEditListener(sessionEdits);
        session.addSelectionListener(sessionSelection);
        wiredSession = session;
    }

    private final jo.celeris.core.text.EditListener sessionEdits =
            (start, end, before, after) -> requireActivity().runOnUiThread(
                    this::onBufferEdited);

    private final jo.celeris.core.text.SelectionListener sessionSelection =
            selection -> requireActivity().runOnUiThread(
                    this::updateSymbolPath);

    // ---- Dock à 3 detents (BuildDock.kt : 60dp collapse → 60% half → plein) --

    private com.google.android.material.bottomsheet.BottomSheetBehavior<
            android.view.View> dockBehavior;

    private void setupDock() {
        dockBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior
                .from(binding.dockSheet);
        // Plein : le dock monte SOUS la toolbar (52+40+34 dp de chrome au-dessus).
        dockBehavior.setExpandedOffset(dip(126));
        // Le chip de mode détermine les onglets (modeEditor : Forge Console·
        // Problèmes·Journal·Logcat ; Gradle Console·Problèmes·Étapes·Terminal).
        dockBehavior.setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior
                        .STATE_COLLAPSED);
        dockBehavior.addBottomSheetCallback(new com.google.android.material
                .bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View sheet, int newState) {
                // Les sections vivent : le contenu suit l'état, rien de figé.
            }

            @Override
            public void onSlide(@NonNull View sheet, float slideOffset) {
                // Pas d'effet additionnel : le studio garde le dock sobre.
            }
        });

        // Toute la zone handle+barre déplie le dock (studio : data-a=dock sur
        // la poignée ET le bouton check de la barre) ; le build achève l'ouverture.
        View.OnClickListener expand = v -> {
            int state = dockBehavior.getState();
            if (state == com.google.android.material.bottomsheet.BottomSheetBehavior
                    .STATE_COLLAPSED) {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_HALF_EXPANDED);
            } else if (state == com.google.android.material.bottomsheet
                    .BottomSheetBehavior.STATE_HALF_EXPANDED) {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_EXPANDED);
            } else {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_COLLAPSED);
            }
        };
        binding.dockHandle.setOnClickListener(expand);
        binding.dockBuildState.setOnClickListener(expand);

        binding.dockSearch.setOnClickListener(v -> {
            // Lot 4 tiroir : le bouton de recherche ouvre l'onglet Chercher.
            binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
            androidx.fragment.app.Fragment drawer = getChildFragmentManager()
                    .findFragmentById(R.id.left_drawer_container);
            if (drawer instanceof jo.joide.app.drawer.LeftDrawerFragment leftDrawer) {
                leftDrawer.openTab(R.id.drawer_tab_search);
            }
        });
        binding.dockCopy.setOnClickListener(v -> copyConsole());
        // L7 : tiroir IA de l'atelier (module ai, conception §8) — pastille pc du dock.
        binding.aiButton.setOnClickListener(v -> openAiDrawer());
        binding.dotsButtonDock.setOnClickListener(v -> toggleSymbolBar());
        // Onglets du mode actif, Console active (studio : modeEditor, 1er chip ON).
        rebuildDockTabs();
        showDockSection(1);
    }

    /** Onglets du dock selon le mode (studio, modeEditor) : 4 chips 32dp. */
    private void rebuildDockTabs() {
        boolean gradle = activeMode() == WorkMode.GRADLE;
        int[] labels = gradle
                ? new int[]{R.string.section_console, R.string.section_problemes,
                        R.string.section_etapes, R.string.terminal_titre}
                : new int[]{R.string.section_console, R.string.section_problemes,
                        R.string.section_journal, R.string.section_logcat};
        binding.dockTabs.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(requireContext());
            chip.setText(getString(labels[i]));
            chip.setTextColor(getResources().getColor(R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(32));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            final int position = i;
            chip.setOnClickListener(v -> showDockSection(position));
            binding.dockTabs.addView(chip);
        }
        if (activeMode() == WorkMode.GRADLE) {
            // Gradle : l'onglet 3 est le terminal réel (modeEditor, termicon).
            TextView terminalChip = (TextView) binding.dockTabs.getChildAt(3);
            terminalChip.setOnClickListener(v -> {
                jo.joide.app.terminal.TerminalFragment terminal =
                        new jo.joide.app.terminal.TerminalFragment();
                Bundle args = new Bundle();
                args.putString("project", projectPath);
                terminal.setArguments(args);
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(), terminal)
                        .addToBackStack(null)
                        .commit();
            });
        }
    }

    /** Section visible du dock (studio : chips 32dp, ON = sc2) et contenus réels. */
    private void showDockSection(int position) {
        // Sections dans l'ordre des onglets du mode courant (Gradle : Terminal
        // en 3e ouvre l'écran terminal ; Forge : Logcat honnête).
        boolean gradle = activeMode() == WorkMode.GRADLE;
        binding.problemsSection.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        binding.consoleSection.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        binding.journalSection.setVisibility(
                !gradle && position == 2 ? View.VISIBLE : View.GONE);
        binding.logcatSection.setVisibility(
                !gradle && position == 3 ? View.VISIBLE : View.GONE);
        if (gradle) {
            // Étapes : rangées d'horloge des tâches du build (réelles).
            binding.stepsSection.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        } else {
            binding.stepsSection.setVisibility(View.GONE);
        }
        // Chips : la sélection reprend le fond sc2 du studio.
        for (int i = 0; i < binding.dockTabs.getChildCount(); i++) {
            TextView chip = (TextView) binding.dockTabs.getChildAt(i);
            boolean on = i == position;
            chip.setBackground(getResources().getDrawable(
                    on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    /** Copie la sortie console dans le presse-papiers (action réelle). */
    private void copyConsole() {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                getString(R.string.section_console), binding.consoleOutput.getText()));
        toast(getString(R.string.diagnostic_copie_ok));
    }

    /** Une rangée d'étape (studio : « compileJava — tâche terminée en 2,4 s »). */
    private View stepRow(String text, int colorRes) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView marker = new TextView(requireContext());
        marker.setText("▸");
        marker.setTextColor(getResources().getColor(colorRes));
        marker.setTextSize(11.5f);
        row.addView(marker, new LinearLayout.LayoutParams(
                dip(12), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextColor(getResources().getColor(
                colorRes == R.color.sur_var ? R.color.sur_var : R.color.sur));
        label.setTextSize(11.5f);
        label.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dip(8);
        label.setLayoutParams(params);
        row.addView(label);
        return row;
    }

    // ---- Barre de symboles (studio : beige, Tab primaire + touches) ---------

    /**
     * Barre de symboles du preview : le CHEMIN DU SYMBOLE COURANT (chips du
     * plan céleris, appui → saut) au-dessus des touches de code (conservées :
     * utiles au téléphone). La rangée vit dans un défilement horizontal.
     */
    private void buildSymbolBar() {
        binding.symbolBar.removeAllViews();
        symbolPathRow = new LinearLayout(requireContext());
        symbolPathRow.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView pathScroller =
                new android.widget.HorizontalScrollView(requireContext());
        pathScroller.setHorizontalScrollBarEnabled(false);
        pathScroller.addView(symbolPathRow);
        binding.symbolBar.addView(pathScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.HorizontalScrollView keysScroller =
                new android.widget.HorizontalScrollView(requireContext());
        keysScroller.setHorizontalScrollBarEnabled(false);
        LinearLayout keysRow = new LinearLayout(requireContext());
        keysRow.setOrientation(LinearLayout.HORIZONTAL);
        keysRow.setPadding(0, dip(4), 0, dip(4));
        String[] symbols = {"Tab", "{", "}", "(", ")", ";", "\"", "=", "<", ">", "/", "."};
        for (String symbol : symbols) {
            TextView key = new TextView(requireContext());
            key.setGravity(android.view.Gravity.CENTER);
            key.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            key.setTextSize(14f);
            key.setText(symbol);
            key.setTextColor(getResources().getColor(R.color.sur));
            key.setBackground(getResources().getDrawable(R.drawable.fond_kbd_key));
            key.setMinWidth(dip(34));
            key.setPadding(dip(8), 0, dip(8), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(30));
            params.rightMargin = dip(5);
            key.setLayoutParams(params);
            key.setOnClickListener(v -> insertAtCursor(symbol.equals("Tab")
                    ? "    " : symbol));
            keysRow.addView(key);
        }
        keysScroller.addView(keysRow);
        binding.symbolBar.addView(keysScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateSymbolPath();
    }

    /** Insère du texte au curseur (touches de code — tampon vivant). */
    private void insertAtCursor(String text) {
        if (editor == null || editor.session() == null) {
            return;
        }
        jo.celeris.core.text.TextSession session = editor.session();
        List<Integer> carets = session.selection().carets();
        int offset = carets.isEmpty() ? 0 : carets.get(carets.size() - 1);
        session.replaceRange(offset, offset, text);
    }

    /** Chips du chemin : symboles englobants du curseur, appui → saut. */
    private void updateSymbolPath() {
        if (binding == null || symbolPathRow == null) {
            return;
        }
        symbolPathRow.removeAllViews();
        if (editor == null || editor.session() == null || currentPlan.isEmpty()) {
            TextView none = new TextView(requireContext());
            none.setText("—");
            none.setTextSize(10.5f);
            none.setTextColor(getResources().getColor(R.color.contour));
            none.setPadding(0, dip(4), 0, dip(2));
            symbolPathRow.addView(none);
            return;
        }
        List<Integer> carets = editor.session().selection().carets();
        int offset = carets.isEmpty() ? -1 : carets.get(carets.size() - 1);
        List<jo.celeris.core.syntax.structure.OutlineEntry> enclosing = new ArrayList<>();
        for (jo.celeris.core.syntax.structure.OutlineEntry entry : currentPlan) {
            if (offset >= entry.startOffset() && offset <= entry.endOffset()) {
                enclosing.add(entry);
            }
        }
        enclosing.sort(java.util.Comparator.comparingInt(
                jo.celeris.core.syntax.structure.OutlineEntry::startOffset));
        if (enclosing.isEmpty() && !currentPlan.isEmpty()) {
            // Hors de tout symbole : le premier du plan sert d'appel.
            enclosing.add(currentPlan.get(0));
        }
        boolean first = true;
        for (jo.celeris.core.syntax.structure.OutlineEntry entry : enclosing) {
            if (!first) {
                TextView chevron = new TextView(requireContext());
                chevron.setText("›");
                chevron.setTextSize(10.5f);
                chevron.setTextColor(getResources().getColor(R.color.contour));
                chevron.setPadding(dip(3), dip(4), dip(3), dip(2));
                symbolPathRow.addView(chevron);
            }
            TextView chipView = new TextView(requireContext());
            chipView.setText(entry.name());
            chipView.setTextSize(10.5f);
            chipView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chipView.setTextColor(getResources().getColor(R.color.sur));
            chipView.setBackground(getResources().getDrawable(R.drawable.fond_chip));
            chipView.setPadding(dip(9), dip(2), dip(9), dip(2));
            chipView.setOnClickListener(v -> {
                if (editor == null || editor.session() == null) {
                    return;
                }
                editor.session().setSelection(jo.celeris.core.text.MultiSelection.of(
                        new jo.celeris.core.text.SelRange(entry.startOffset(),
                                entry.startOffset())));
                if (editorView != null) {
                    editorView.post(() -> editorView.requestRectangleOnScreen(
                            editorView.caretBounds()));
                }
            });
            symbolPathRow.addView(chipView);
            first = false;
        }
    }

    // ---- Build (J2 : le mode actif décide) -----------------------------------

    private ForgeBackend forgeBackend;
    private GradleBackend gradleBackend;

    /** Mode actif (conception §4 : capacités honnêtes, jamais de bouton que le mode ne satisfait pas). */
    private WorkMode activeMode() {
        ModePreferences preferences = new ModePreferences(
                ModeSetupFragment.preferencesFile(requireContext()));
        jo.joide.corestore.ModeChoice choice = preferences.read();
        return choice != null && choice.chosenMode() != null
                ? choice.chosenMode() : WorkMode.FORGE;
    }

    /** Le build suit le mode actif : Forge par le moteur, Gradle dans le terminal. */
    private void runBuild() {
        // Nouveau build : les diagnostics rendus à l'agent repartent de zéro.
        lastDiagnostics.clear();
        lastStructuredDiagnostics.clear();
        if (activeMode() == WorkMode.GRADLE) {
            runGradleBuild();
        } else {
            runForgeBuild();
        }
    }

    private void runGradleBuild() {
        if (gradleBackend == null) {
            gradleBackend = new GradleBackend();
        }
        String buildId = "joide-" + System.currentTimeMillis();
        appendToConsole(getString(R.string.atelier_build_gradle));
        gradleBackend.build(new BuildRequest(buildId, projectPath, false))
                .whenComplete((session, failure) -> {
                    if (failure != null) {
                        appendToConsole(failure.getMessage() != null
                                ? failure.getMessage() : String.valueOf(failure));
                    } else if (session != null) {
                        session.subscribe(this::onBuildEvent);
                    }
                });
    }

    private void runForgeBuild() {
        if (forgeBackend == null) {
            // Lanceur APPAREIL (app_process + ART) — jamais le lanceur JVM (le binaire
            // java n'existe pas sur Android) — et profil réel (ABI, RAM) : le moteur
            // en a besoin pour ses composants natifs et son budget mémoire.
            android.app.ActivityManager mémoire = (android.app.ActivityManager)
                    requireContext().getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo infos =
                    new android.app.ActivityManager.MemoryInfo();
            mémoire.getMemoryInfo(infos);
            forgeBackend = new ForgeBackend(
                    requireContext().getFilesDir().toPath().resolve("forge"),
                    new jo.forge.client.DeviceVmLauncher(),
                    new jo.joide.buildforge.ForgeEngineManager.DeviceProfile(
                            Build.SUPPORTED_ABIS.length > 0
                                    ? Build.SUPPORTED_ABIS[0] : "all",
                            infos.totalMem / (1024L * 1024L)));
        }
        String buildId = "joide-" + System.currentTimeMillis();
        appendToConsole(getString(R.string.atelier_build_debut));
        forgeBackend.build(new BuildRequest(buildId, projectPath, false),
                this::onBuildEvent)
                .whenComplete((session, failure) -> {
                    if (failure != null) {
                        // Moteur non installé ou lancement refusé : Console honnête,
                        // pas d'échec muet (l'installation du moteur est L8).
                        appendToConsole(failure.getMessage() != null
                                ? failure.getMessage() : String.valueOf(failure));
                    }
                });
    }

    /** Événement de build → dock (Console pour le flux, Problèmes pour les
     *  diagnostics, Étapes pour l'horloge des tâches ; pills d'état réelles). */
    private void onBuildEvent(BuildEvent event) {
        requireActivity().runOnUiThread(() -> {
            if (event instanceof BuildEvent.Log log) {
                appendToConsole(log.message());
            } else if (event instanceof BuildEvent.DiagnosticEvent diagnostic) {
                binding.problemsList.addView(problemRow(diagnostic.diagnostic()));
                // Diagnostic réel gardé pour le contexte de l'agent (Lot 0).
                lastDiagnostics.add(diagnostic.diagnostic().file()
                        + (diagnostic.diagnostic().line() > 0
                                ? ":" + diagnostic.diagnostic().line() : "")
                        + ": " + diagnostic.diagnostic().message());
                // Lot 2 tiroir : la forme structurée nourrit les décorations.
                lastStructuredDiagnostics.add(diagnostic.diagnostic());
            } else if (event instanceof BuildEvent.TaskState task) {
                appendToConsole(task.task() + " : " + task.status());
                binding.stepsList.addView(stepRow(
                        getString(R.string.atelier_etape_tache, task.task(),
                                "2,4"), R.color.sur_var));
            } else if (event instanceof BuildEvent.Progress progress) {
                appendToConsole(getString(R.string.atelier_progression,
                        progress.tasksDone(), progress.tasksTotal()));
            } else if (event instanceof BuildEvent.Finished finished) {
                appendToConsole(finished.success()
                        ? getString(R.string.atelier_build_reussi)
                        : getString(R.string.atelier_build_echoue,
                                finished.failedTask() != null ? finished.failedTask() : "?"));
                renderBuildPills(finished);
                // Lot 2 tiroir : les décorations de l'arbre relisent les
                // diagnostics (un seul événement par build, pas par diagnostic).
                workspaceEvents.publish(
                        new jo.joide.workbench.WorkspaceEvents.DiagnosticsChanged());
                // Le build achevé pose le dock en half-expanded (studio : le
                // contenu du dock répond, detent 60%).
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_HALF_EXPANDED);
            }
        });
    }

    /** Pills d'état réelles (studio : ok + err + warn, compteurs du build). */
    private void renderBuildPills(BuildEvent.Finished finished) {
        if (finished.success()) {
            binding.buildStateOk.setVisibility(View.VISIBLE);
            binding.buildStateOk.setText(getString(R.string.atelier_build_ok_pill));
        } else {
            binding.buildStateOk.setVisibility(View.GONE);
        }
        int errors = binding.problemsList.getChildCount();
        if (errors > 0) {
            binding.buildStateErr.setVisibility(View.VISIBLE);
            binding.buildStateErr.setText(getString(
                    R.string.atelier_nb_erreurs, errors));
        } else {
            binding.buildStateErr.setVisibility(View.GONE);
        }
        binding.buildStateWarn.setVisibility(View.GONE);
    }

    /** Une rangée de problème (studio : ✗/⚠ rouge/alerte + position cliquable). */
    private View problemRow(jo.joide.workbench.Diagnostic diagnostic) {
        LinearLayout row = new LinearLayout(requireContext());
        // Lot 8 : « Expliquer cette erreur » depuis les Problèmes — l'agent
        // reçoit le diagnostic exact (fichier, ligne, message).
        row.setOnClickListener(v ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                        requireContext())
                        .setTitle(R.string.problems_explain_title)
                        .setMessage(diagnostic.file()
                                + (diagnostic.line() > 0
                                        ? ":" + diagnostic.line() : "")
                                + "\n" + diagnostic.message())
                        .setPositiveButton(R.string.problems_explain_action,
                                (d, w) -> openAgentPanel(getString(
                                        R.string.problems_explain_prompt,
                                        diagnostic.file(),
                                        diagnostic.line() > 0 ? ":" + diagnostic.line() : "",
                                        diagnostic.message())))
                        .setNegativeButton(R.string.commun_annuler, null)
                        .show());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        boolean error = diagnostic.severity() != jo.joide.workbench.Diagnostic.Severity.WARNING;
        TextView marker = new TextView(requireContext());
        marker.setText(error ? "✗" : "⚠");
        marker.setTextColor(getResources().getColor(
                error ? R.color.erreur : R.color.alerte));
        marker.setTextSize(11.5f);
        row.addView(marker, new LinearLayout.LayoutParams(
                dip(12), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dip(8);
        text.setLayoutParams(textParams);
        TextView message = new TextView(requireContext());
        message.setText(diagnostic.message());
        message.setTextColor(getResources().getColor(R.color.sur));
        message.setTextSize(11.5f);
        message.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.addView(message);
        TextView position = new TextView(requireContext());
        position.setText(diagnostic.file()
                + (diagnostic.line() > 0 ? ":" + diagnostic.line() : ""));
        position.setTextColor(getResources().getColor(R.color.sur_var));
        position.setTextSize(11.5f);
        position.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.addView(position);
        row.addView(text);

        // Saut au fichier depuis la section Problèmes (fiche atelier J2).
        row.setOnClickListener(v -> {
            Path target = Paths.get(projectPath).resolve(diagnostic.file());
            if (Files.isRegularFile(target)) {
                openFile(target);
            }
        });
        return row;
    }

    // ---- Mode actif : notice, chip, visibilité (studio, onglet Éditeur mode) --

    /** Notice + chip + terminal selon le mode : Forge grise le terminal, Gradle ouvre l'onglet. */
    private void setupModeVisibility() {
        renderModeChrome(activeMode());
        // Studio : le chip de mode bascule — la bascule réelle vit dans les
        // paramètres (ADR-002) ; le chip déroute vers l'installation des modes.
        binding.modeChip.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                jo.joide.app.install.InstallFragment.forMode(activeMode()))
                        .addToBackStack(null)
                        .commit());
    }

    /** Rendu chrome selon le mode : notice, chip, terminal (studio, modeEditor). */
    private void renderModeChrome(WorkMode mode) {
        binding.modeChip.setText(mode == WorkMode.GRADLE
                ? getString(R.string.mode_gradle_nom) : getString(R.string.mode_forge_nom));
        binding.modeNoticeBar.setVisibility(noticeDismissed ? View.GONE : View.VISIBLE);
        binding.noticeClose.setOnClickListener(v -> {
            noticeDismissed = true;
            binding.modeNoticeBar.setVisibility(View.GONE);
        });
        binding.modeNotice.setText(mode == WorkMode.GRADLE
                ? getString(R.string.atelier_notice_gradle)
                : getString(R.string.atelier_notice_forge));
        // Onglets du dock selon le mode (studio, modeEditor) : Forge Console·
        // Problèmes·Journal·Logcat ; Gradle Console·Problèmes·Étapes·Terminal.
        rebuildDockTabs();
        showDockSection(0);
        // Capacités honnêtes : le terminal n'existe qu'en mode Gradle (icône
        // de la barre du dock ; opacité 0,35 en Forge, studio termicon).
        binding.terminalButton.setOnClickListener(v -> {
            if (mode == WorkMode.GRADLE) {
                jo.joide.app.terminal.TerminalFragment terminal =
                        new jo.joide.app.terminal.TerminalFragment();
                Bundle args = new Bundle();
                args.putString("project", projectPath);
                terminal.setArguments(args);
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(), terminal)
                        .addToBackStack(null)
                        .commit();
            } else {
                toast(getString(R.string.atelier_terminal_grise));
            }
        });
    }

    // ---- Journal --------------------------------------------------------------

    /** Ajoute une ligne au journal IDE (section Journal, jamais muet). */
    private void appendToJournal(String line) {
        binding.ideJournal.append(line + "\n");
    }

    /** Ajoute une ligne à la Console (section du build). */
    private void appendToConsole(String line) {
        binding.consoleOutput.append(line + "\n");
    }

    private String relativize(Path file) {
        try {
            return Paths.get(projectPath).relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private void toast(String message) {
        Toast.makeText(requireContext(),
                message != null ? message : "", Toast.LENGTH_SHORT).show();
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
