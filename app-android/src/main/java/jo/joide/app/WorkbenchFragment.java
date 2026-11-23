package jo.joide.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jo.joide.app.R;
import jo.joide.app.config.ErrorSheet;
import jo.joide.app.databinding.WorkbenchFragmentBinding;
import jo.joide.corestore.ModePreferences;
import jo.joide.app.ModeSetupFragment;
import jo.joide.buildforge.ForgeBackend;
import jo.joide.buildgradle.GradleBackend;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.ConsoleBuffer;
import jo.joide.workbench.Durations;
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
 * <p>Dock conforme au preview <i>bottom-sheet</i> (docs/mockups) : feuille à
 * trois détents (réduit → mi-hauteur → plein calé sous la barre d'outils),
 * console de build rendue depuis un {@link ConsoleBuffer} — lignes colorées
 * par nature, tâches remplacées en place, progression collante, bilan avec
 * durée et compte réels —, auto-suivi de la sortie sauf si le développeur a
 * remonté, build échoué qui ouvre la feuille sur Problèmes, build réussi
 * qui ne vole jamais le focus, repli de la feuille sous le clavier.</p>
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

    /** Avis de build masqué par l'utilisateur (bandeau unifié, session seule). */
    private boolean avisBuildMasqué;

    /** Avis d'index masqué par l'utilisateur (bandeau unifié, session seule). */
    private boolean avisIndexMasqué;

    /** Dernier statut d'index vu non-prêt après une passe (avis honnête). */
    private boolean indexInterrompu;

    /** Bandeau unifié déplié (plusieurs avis, référence EditorNoticeStrip). */
    private boolean avisDépliés;
    private String projectPath;

    // ---- LSP (LOT-11, B-02) ---------------------------------------------------

    /** Les sessions LSP du projet (moteurs Java/Kotlin/XML de code-lsp v1.15). */
    private jo.joide.app.lsp.LspSessions sessionsLsp;

    /** Le tick de l'état d'index (la puce et le dialogue s'y rafraîchissent). */
    private final android.os.Handler tickIndex = new android.os.Handler();

    /** Le tick court-il ? (posé au premier état CONSTRUCTION, endormi au repos). */
    private boolean tickIndexActif;

    /** Compteur de repos du tick (~2 s après la fin d'un pass, il s'endort). */
    private int reposIndex;

    /** Dernière phase LSP vue (transitions vers la console et le journal). */
    private jo.codeide.lsp.host.PhaseIndexation dernièrePhaseIndex;

    /** Les diagnostics LSP du document actif (squiggles + bande céleris). */
    private final List<jo.celeris.android.host.spi.HostDiagnostic> diagnosticsLsp =
            new java.util.ArrayList<>();

    /** Poignée de retrait de l'auditeur de diagnostics du pont client. */
    private Runnable retraitDiagnosticsLsp;

    /** L'uri du document actif côté moteurs (file://…). */
    private String uriDocumentActif;

    /** Le dialogue d'état d'index ouvert (null fermé) et ses vues vives. */
    private android.app.Dialog dialogueIndex;
    private android.view.View dialogueIndexCorps;
    private android.widget.ProgressBar dialogueIndexBarre;
    private android.widget.TextView dialogueIndexCompte;
    private android.widget.TextView dialogueIndexSous;
    private android.widget.LinearLayout dialogueIndexListe;
    private android.widget.LinearLayout dialogueIndexIndexeurs;
    private android.widget.TextView dialogueIndexDurées;
    private android.widget.TextView dialogueIndexCache;

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
            // L10 : l'indexation démarre À L'OUVERTURE du projet — pour que les
            // fournisseurs de langue s'en servent (structure, symboles, complétion
            // à venir). Cède au build, incrémentale à la sauvegarde.
            démarrerIndexation();
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
        // Segments Code / Aperçu / Scindé (référence ViewModeToggle : le
        // scindé n'existe que pour un fichier prévisualisable textuel).
        binding.segmentCode.setOnClickListener(v -> renderVue(VueMode.CODE));
        binding.segmentApercu.setOnClickListener(v -> montrerApercu(VueMode.APERCU));
        binding.segmentScinde.setOnClickListener(v -> montrerApercu(VueMode.SCINDE));
        // Fil d'Ariane : le texte ouvre la Structure (référence), les flèches
        // suivent l'historique de navigation des fichiers.
        binding.breadcrumb.setOnClickListener(v -> {
            binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
            androidx.fragment.app.Fragment drawer = getChildFragmentManager()
                    .findFragmentById(R.id.left_drawer_container);
            if (drawer instanceof jo.joide.app.drawer.LeftDrawerFragment leftDrawer) {
                leftDrawer.openTab(R.id.drawer_tab_structure);
            }
        });
        binding.filRetour.setOnClickListener(v -> naviguer(-1));
        binding.filAvant.setOnClickListener(v -> naviguer(1));
        // Puce d'indexation : le détail du pass (référence IndexStatusChip).
        binding.indexChip.setOnClickListener(v -> montrerDétailIndex());
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

    // ---- Segments Code / Aperçu / Scindé (référence EditorViewMode) ---------

    /** Modes de vue du fichier actif (référence : Text / Preview / Split). */
    private enum VueMode { CODE, APERCU, SCINDE }

    /** Mode courant (scindé = empilé sur téléphone, comme la référence). */
    private VueMode vueMode = VueMode.CODE;

    /** Un fichier a-t-il un aperçu (XML filaire ou image) ? */
    private static boolean aperçuPossible(String name) {
        return name.endsWith(".xml") || isImageName(name);
    }

    /** Retour au Code : l'éditeur céleris reprend la place. */
    private void showCodeView() {
        renderVue(VueMode.CODE);
    }

    /** Aperçu ou scindé : rend le contenu d'aperçu PUIS bascule la vue. */
    private void montrerApercu(VueMode cible) {
        if (activeFile == null) {
            appendToJournal(getString(R.string.atelier_apercu_indisponible));
            return;
        }
        // Scindé sans code possible (binaire/image) : aperçu seul, honnête.
        if (cible == VueMode.SCINDE && !activeTextual) {
            appendToJournal(getString(R.string.atelier_apercu_indisponible));
            cible = VueMode.APERCU;
        }
        String name = activeFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml")) {
            String content = editor != null && editor.session() != null
                    && editorView != null
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
            renderVue(cible);
            return;
        }
        // Images : l'état binaire EST l'aperçu (déjà rendu si binaire).
        if (isImageName(name)) {
            try {
                showBinaryState(activeFile, Files.size(activeFile));
                renderVue(VueMode.APERCU);
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
        } catch (Exception e) {
            // XML invalide (en cours de frappe) : honnête, pas de filaire figé.
            appendToJournal(getString(R.string.atelier_apercu_indisponible));
            renderVue(VueMode.CODE);
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
            renderVue(VueMode.CODE);
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

    /**
     * Rend la vue demandée (référence ViewModeToggle + EditorCenter) : CODE =
     * l'éditeur seul ; APERCU = le filaire seul ; SCINDE = les deux empilés
     * (code dessus, aperçu dessous — téléphone). Les segments suivent, le
     * scindé n'apparaît que pour un fichier prévisualisable textuel.
     */
    private void renderVue(VueMode cible) {
        if (binding == null) {
            return;
        }
        vueMode = cible;
        binding.etatVide.setVisibility(View.GONE);
        boolean code = cible == VueMode.CODE || cible == VueMode.SCINDE;
        boolean aperçu = cible == VueMode.APERCU || cible == VueMode.SCINDE;
        // L'aperçu ne se montre que s'il a du contenu (le filaire vient d'y
        // être rendu par montrerApercu ; les images passent par l'état binaire).
        boolean aperçuPrêt = binding.apercuZone.getChildCount() > 0
                || (activeFile != null && !activeTextual);
        aperçu = aperçu && (aperçuPrêt || cible != VueMode.SCINDE);
        if (editorView != null) {
            editorView.setVisibility(activeFile != null && activeTextual && code
                    ? View.VISIBLE : View.GONE);
        }
        binding.apercuZone.setVisibility(aperçu ? View.VISIBLE : View.GONE);
        // Scindé : deux moitiés empilées (code dessus, aperçu dessous).
        if (cible == VueMode.SCINDE && aperçu) {
            binding.apercuZone.post(() -> {
                if (binding == null) {
                    return;
                }
                int hauteur = Math.max(1, binding.editorArea.getHeight() / 2);
                android.widget.FrameLayout.LayoutParams demiAperçu =
                        new android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, hauteur,
                                android.view.Gravity.BOTTOM);
                binding.apercuZone.setLayoutParams(demiAperçu);
                if (editorView != null) {
                    android.widget.FrameLayout.LayoutParams demiCode =
                            new android.widget.FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, hauteur,
                                    android.view.Gravity.TOP);
                    editorView.setLayoutParams(demiCode);
                }
            });
        } else {
            android.widget.FrameLayout.LayoutParams plein =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
            binding.apercuZone.setLayoutParams(plein);
            if (editorView != null) {
                editorView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        // Segments : l'actif sur la pastille primaire (studio).
        styleSegment(binding.segmentCode, cible == VueMode.CODE);
        styleSegment(binding.segmentApercu, cible == VueMode.APERCU);
        styleSegment(binding.segmentScinde, cible == VueMode.SCINDE);
        // Visibilité des segments (référence : Preview/Split seulement si
        // le fichier se prévisualise ; Split exige en plus du code).
        boolean possible = activeFile != null
                && aperçuPossible(activeFile.getFileName().toString().toLowerCase(Locale.ROOT));
        binding.segmentApercu.setVisibility(possible ? View.VISIBLE : View.GONE);
        binding.segmentScinde.setVisibility(
                possible && activeTextual ? View.VISIBLE : View.GONE);
        if (cible == VueMode.SCINDE && !possible && activeFile != null) {
            // Rien à prévisualiser : le scindé retombe sur le code, honnête.
            vueMode = VueMode.CODE;
            styleSegment(binding.segmentCode, true);
            styleSegment(binding.segmentScinde, false);
        }
    }

    /** Style d'un segment : actif sur la pastille primaire (studio). */
    private void styleSegment(android.widget.ImageButton segment, boolean actif) {
        segment.setBackground(actif
                ? getResources().getDrawable(R.drawable.fond_segment_actif) : null);
        segment.setColorFilter(getResources().getColor(actif
                ? R.color.sur_conteneur_primaire : R.color.sur_var));
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
            } else if (event instanceof jo.joide.workbench.WorkspaceEvents.FileSaved saved) {
                changed = saved.file();
            } else if (event instanceof jo.joide.workbench.WorkspaceEvents.ModelChanged modèle) {
                // forge.json réécrit (écrans Modules) : les racines sources ont
                // pu changer — repasse d'index au fil de fond (L10).
                relancerIndexation();
            } else if (event instanceof jo.joide.workbench.WorkspaceEvents.BuildFinished build) {
                // Un build vient de finir : les classpath résolus fraîchement
                // écrits par le moteur grossissent le périmètre des jars (L10).
                relancerIndexation();
            }
            // Indexation incrémentale (L10) : le fichier changé/sauvegardé
            // re-indexe SEUL — coût O(fichier), sur le fil de fond.
            if (changed != null && index != null
                    && (event instanceof jo.joide.workbench.WorkspaceEvents.FileChangedOnDisk
                        || event instanceof jo.joide.workbench.WorkspaceEvents.FileSaved
                        || event instanceof jo.joide.workbench.WorkspaceEvents.FileCreated)) {
                java.nio.file.Path fichierIndexé = changed;
                indexExecutor.execute(() -> {
                    try {
                        index.reindexSource(fichierIndexé,
                                new String(Files.readAllBytes(fichierIndexé),
                                        StandardCharsets.UTF_8));
                    } catch (IOException | RuntimeException illisible) {
                        // binaire/illisible : rien à indexer — honnête.
                    }
                });
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
        ouvrirFichier(file, true);
    }

    /**
     * Ouverture réelle : tampon en cache, historique de navigation (flèches du
     * fil d'Ariane, référence navHistory) et récents (état vide) — sauf en
     * navigation programme (retour/avant), qui déplace le curseur sans pousser.
     */
    private void ouvrirFichier(Path file, boolean pousserHistorique) {
        // Le tampon quitté vit en cache : revenir retrouve les modifications.
        snapshotCurrentBuffer();
        activeFile = file;
        if (!openTabs.contains(file)) {
            openTabs.add(file);
        }
        if (pousserHistorique) {
            // Historique de navigation (référence navHistory) : on tronque
            // l'avant, on pousse, le curseur d'historique suit.
            if (navPosition >= 0 && navPosition < historiqueNav.size() - 1) {
                historiqueNav.subList(navPosition + 1, historiqueNav.size()).clear();
            }
            if (historiqueNav.isEmpty()
                    || !historiqueNav.get(historiqueNav.size() - 1).equals(file)) {
                historiqueNav.add(file);
            }
            navPosition = historiqueNav.size() - 1;
        }
        mémoriserRécent(file);
        String cached = modifiedBuffers.get(file);
        if (cached != null) {
            attachEditor(file.getFileName().toString(), cached);
            currentDirty = true;
        } else {
            openFromDisk(file);
        }
        // Un fichier textuel ouvert part du code (référence : Text à
        // l'ouverture) ; un binaire garde son état-aperçu (showBinaryState).
        if (activeTextual) {
            renderVue(VueMode.CODE);
        }
        rebuildTabs();
        refreshFileChrome();
        checkSafetyCopy(file);
    }

    /** Recule d'un cran dans l'historique de navigation (flèche ←). */
    private void naviguer(int delta) {
        int cible = navPosition + delta;
        if (cible < 0 || cible >= historiqueNav.size()) {
            return;
        }
        navPosition = cible;
        Path fichier = historiqueNav.get(cible);
        // Déplacement dans l'historique : pas de nouveau cran poussé.
        if (!fichier.equals(activeFile)) {
            ouvrirFichier(fichier, false);
        }
        refreshFileChrome();
    }

    /** Mémoire des récents (état vide — référence NoOpenFilesView : 5 max). */
    private void mémoriserRécent(Path file) {
        récents.remove(file);
        récents.addFirst(file);
        while (récents.size() > 5) {
            récents.removeLast();
        }
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
                renderVue(VueMode.APERCU);
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
        renderVue(VueMode.APERCU);
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

    /**
     * Fil d'Ariane + sous-titre (branche · fichier actif) à chaque ouverture.
     * Le fil suit ensuite le curseur (màjFilAriane, anti-rebond 200 ms comme
     * la référence) ; les flèches suivent l'historique de navigation.
     */
    private void refreshFileChrome() {
        màjFilAriane();
        binding.projectSubtitle.setText(subtitleBranch != null && activeFile != null
                ? subtitleBranch + " · " + activeFile.getFileName()
                : subtitleBranch != null ? subtitleBranch
                : activeFile != null ? activeFile.getFileName().toString() : "");
        binding.projectSubtitle.setVisibility(
                binding.projectSubtitle.getText().length() > 0 ? View.VISIBLE : View.GONE);
        màjFlèchesNav();
    }

    /** Flèches du fil d'Ariane : visibles dès qu'un retour/avant existe. */
    private void màjFlèchesNav() {
        boolean peutReculer = navPosition > 0;
        boolean peutAvancer = navPosition >= 0 && navPosition < historiqueNav.size() - 1;
        binding.filRetour.setVisibility(peutReculer ? View.VISIBLE : View.GONE);
        binding.filAvant.setVisibility(peutAvancer ? View.VISIBLE : View.GONE);
        binding.filRetour.setAlpha(peutReculer ? 1f : 0.35f);
        binding.filAvant.setAlpha(peutAvancer ? 1f : 0.35f);
    }

    /**
     * Le fil d'Ariane suit le curseur (référence BreadcrumbBar : module ›
     * symboles englobants, anti-rebond 200 ms, repli honnête sur le chemin
     * du fichier hors de tout symbole).
     */
    private void màjFilAriane() {
        if (binding == null) {
            return;
        }
        String repli = (activeFile != null ? relativize(activeFile) : relativize(Paths.get(projectPath)))
                .replace('/', '›') + getString(R.string.atelier_fil_point);
        if (editor == null || editor.session() == null || currentPlan.isEmpty()
                || activeFile == null) {
            binding.breadcrumb.setText(repli);
            return;
        }
        List<Integer> carets = editor.session().selection().carets();
        int offset = carets.isEmpty() ? -1 : carets.get(carets.size() - 1);
        List<String> noms = new ArrayList<>();
        for (jo.celeris.core.syntax.structure.OutlineEntry entry : currentPlan) {
            if (offset >= entry.startOffset() && offset <= entry.endOffset()) {
                noms.add(entry.name());
            }
        }
        if (noms.isEmpty()) {
            binding.breadcrumb.setText(repli);
            return;
        }
        String dossier = relativize(activeFile.getParent() != null
                ? activeFile.getParent() : activeFile).replace('/', '›');
        binding.breadcrumb.setText(dossier + "›" + String.join("›", noms));
    }

    /** Anti-rebond du fil d'Ariane (référence : 200 ms après le dernier déplacement). */
    private void màjFilArianeDifférée() {
        if (filTâche != null) {
            filHandler.removeCallbacks(filTâche);
        }
        filTâche = this::màjFilAriane;
        filHandler.postDelayed(filTâche, 200);
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

    /**
     * Ouvre l'écran Modules (lot L10) : configuration du modèle réel
     * (modules, sources, facette Android, dépendances, dépôts).
     */
    @Override
    public void openModulesScreen() {
        binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(),
                        jo.joide.app.config.ModulesFragment.forProject(projectPath))
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

    /** Le forge.json a été réécrit hors de l'atelier (écrans Modules — L10). */
    private static volatile boolean pendingModelChanged;

    /** Une re-résolution est demandée (onglet Dépendances — L10). */
    private static volatile boolean pendingRebuild;

    /** Signale une réécriture du forge.json (consommée à la reprise). */
    public static void notifyModelChanged() {
        pendingModelChanged = true;
    }

    /** Demande une re-résolution : le build repartira à la reprise (L10). */
    public static void requestRebuild() {
        pendingRebuild = true;
    }

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
        if (binding == null || projectPath == null) {
            return;
        }
        // Le modèle de build a été réécrit depuis l'écran Modules : l'arbre et
        // les détecteurs relisent le disque (un seul chemin d'update — Lot 8).
        if (pendingModelChanged) {
            pendingModelChanged = false;
            workspaceEvents.publish(new jo.joide.workbench.WorkspaceEvents
                    .ModelChanged(java.nio.file.Paths.get(projectPath, "forge.json")));
        }
        // Re-résolution demandée (la résolution vit dans le moteur) : build.
        if (pendingRebuild) {
            pendingRebuild = false;
            runBuild();
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

    /**
     * Menu d'options de la barre (⋯), structuré comme la référence
     * (EditorOverflowMenu) : action du fichier actif, bascules à ÉTAT (cochées
     * quand actives), navigation, puis le mode en dernier. Le Run reste inline
     * dans la barre — la référence ne le met jamais ici.
     */
    private void showOptionsMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.workbench_options);
        // États des bascules calculés à l'ouverture (jamais figés).
        boolean iaOuvert = binding.drawerLayout.isDrawerOpen(
                androidx.core.view.GravityCompat.END);
        boolean consoleOuverte = dockBehavior != null && dockBehavior.getState()
                != com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
        boolean symbolesVisibles = binding.symbolBar.getVisibility() == View.VISIBLE;
        boolean aperçuActif = vueMode == VueMode.APERCU;
        menu.getMenu().findItem(R.id.option_refaire)
                .setVisible(activeFile != null && activeTextual);
        menu.getMenu().findItem(R.id.option_agent_ia).setChecked(iaOuvert);
        menu.getMenu().findItem(R.id.option_console).setChecked(consoleOuverte);
        menu.getMenu().findItem(R.id.option_symboles).setChecked(symbolesVisibles);
        menu.getMenu().findItem(R.id.option_apercu).setChecked(aperçuActif);
        menu.getMenu().findItem(R.id.option_apercu).setVisible(activeFile != null
                && aperçuPossible(activeFile.getFileName().toString().toLowerCase(Locale.ROOT)));
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.option_fichiers) {
                binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
            } else if (id == R.id.option_modules) {
                openModulesScreen();
            } else if (id == R.id.option_activite) {
                // Journal d'activité (L10) : chronologie builds/index/crash.
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new jo.joide.app.config.ActivityFragment())
                        .addToBackStack(null)
                        .commit();
            } else if (id == R.id.option_agent_ia) {
                if (iaOuvert) {
                    binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END);
                } else {
                    openAiDrawer();
                }
            } else if (id == R.id.option_terminal) {
                binding.terminalButton.performClick();
            } else if (id == R.id.option_recherche) {
                binding.dockSearch.performClick();
            } else if (id == R.id.option_console) {
                dockBehavior.setState(consoleOuverte
                        ? com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                        : com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED);
            } else if (id == R.id.option_symboles) {
                toggleSymbolBar();
            } else if (id == R.id.option_apercu) {
                if (aperçuActif) {
                    renderVue(VueMode.CODE);
                } else {
                    montrerApercu(VueMode.APERCU);
                }
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

    /** Historique de navigation des fichiers (référence navHistory — flèches). */
    private final List<Path> historiqueNav = new ArrayList<>();

    /** Curseur dans l'historique (−1 : rien). */
    private int navPosition = -1;

    /** Récents (état vide — référence NoOpenFilesView : 5 derniers, tape = rouvre). */
    private final java.util.ArrayDeque<Path> récents = new java.util.ArrayDeque<>();

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
        // Chevron de débordement : le menu de tous les fichiers ouverts.
        binding.ongletsDebordement.setOnClickListener(v -> montrerOngletsDébordement());
    }

    /**
     * Reconstruit la rangée d'onglets (référence TabsStrip : ✱ par tampon,
     * croix, PAS de bouton ＋ — la création vit dans l'arbre du tiroir) ;
     * le chevron de débordement n'apparaît que si les onglets débordent.
     */
    private void rebuildTabs() {
        binding.filesTabs.removeAllViews();
        for (Path file : openTabs) {
            binding.filesTabs.addView(fileTab(file, file.equals(activeFile)));
        }
        // Chevron épinglé si (et seulement si) la strip déborde (référence).
        binding.ongletsDefile.post(() -> {
            if (binding == null) {
                return;
            }
            boolean déborde = binding.ongletsDefile.canScrollHorizontally(-1)
                    || binding.ongletsDefile.canScrollHorizontally(1);
            binding.ongletsDebordement.setVisibility(
                    déborde && !openTabs.isEmpty() ? View.VISIBLE : View.GONE);
            binding.ongletsSep.setVisibility(
                    déborde && !openTabs.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    /** Chevron de débordement : le menu de TOUS les fichiers ouverts. */
    private void montrerOngletsDébordement() {
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(),
                        binding.ongletsDebordement);
        for (int i = 0; i < openTabs.size(); i++) {
            Path file = openTabs.get(i);
            android.view.MenuItem item = menu.getMenu().add(0, i, i,
                    file.getFileName().toString());
            if (file.equals(activeFile)) {
                item.setTitle("• " + file.getFileName().toString());
            }
        }
        menu.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            if (index >= 0 && index < openTabs.size()) {
                openFile(openTabs.get(index));
            }
            return true;
        });
        menu.show();
    }

    /**
     * État honnête sans fichier : la zone dit quoi faire, et les récents
     * rouvrent en une tape (référence NoOpenFilesView : 5 derniers, dossier
     * au-dessous, jamais une zone muette).
     */
    private void showEmptyState() {
        if (editorView != null) {
            editorView.setVisibility(View.GONE);
        }
        binding.apercuZone.setVisibility(View.GONE);
        binding.etatVide.setVisibility(View.VISIBLE);
        binding.etatVideRecents.removeAllViews();
        if (!récents.isEmpty()) {
            binding.etatVideRecents.setVisibility(View.VISIBLE);
            TextView titre = new TextView(requireContext());
            titre.setText(R.string.atelier_recents_titre);
            titre.setTextSize(12f);
            titre.setTypeface(titre.getTypeface(), android.graphics.Typeface.BOLD);
            titre.setTextColor(getResources().getColor(R.color.sur_var));
            titre.setPadding(0, 0, 0, dip(6));
            binding.etatVideRecents.addView(titre);
            for (Path récent : récents) {
                binding.etatVideRecents.addView(rangéeRécent(récent));
            }
        } else {
            binding.etatVideRecents.setVisibility(View.GONE);
        }
        binding.breadcrumb.setText(relativize(Paths.get(projectPath))
                .replace('/', '›') + getString(R.string.atelier_fil_point));
        màjFlèchesNav();
    }

    /** Une rangée de récent : nom + dossier relatif, tape = rouvre. */
    private View rangéeRécent(Path file) {
        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.VERTICAL);
        rangée.setPadding(dip(12), dip(8), dip(12), dip(8));
        rangée.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dip(6);
        rangée.setLayoutParams(params);
        TextView nom = new TextView(requireContext());
        nom.setText(file.getFileName().toString());
        nom.setTextSize(13.5f);
        nom.setTypeface(nom.getTypeface(), android.graphics.Typeface.BOLD);
        nom.setTextColor(getResources().getColor(R.color.sur));
        rangée.addView(nom);
        String dossier = relativize(file.getParent() != null
                ? file.getParent() : file).replace('/', '›');
        TextView chemin = new TextView(requireContext());
        chemin.setText(getString(R.string.atelier_recents_dossier, dossier));
        chemin.setTextSize(11f);
        chemin.setTextColor(getResources().getColor(R.color.contour));
        rangée.addView(chemin);
        rangée.setOnClickListener(v -> openFile(file));
        return rangée;
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

    /**
     * Menu d'onglet, appui long (référence EditorTab : Fermer · Fermer les
     * autres · Fermer à droite · Fermer à gauche · Tout fermer · Copier le
     * chemin — les entrées impossibles sont absentes, pas grisées-fantômes).
     */
    private void showTabMenu(Path file, View anchor) {
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        int index = openTabs.indexOf(file);
        int ordre = 0;
        menu.getMenu().add(0, 1, ordre++, R.string.atelier_onglet_fermer);
        if (openTabs.size() > 1) {
            menu.getMenu().add(0, 2, ordre++, R.string.atelier_onglet_fermer_autres);
        }
        if (index >= 0 && index < openTabs.size() - 1) {
            menu.getMenu().add(0, 4, ordre++, R.string.atelier_onglet_fermer_droite);
        }
        if (index > 0) {
            menu.getMenu().add(0, 5, ordre++, R.string.atelier_onglet_fermer_gauche);
        }
        if (openTabs.size() > 1) {
            menu.getMenu().add(0, 6, ordre++, R.string.atelier_onglet_tout_fermer);
        }
        menu.getMenu().add(0, 3, ordre, R.string.atelier_onglet_copier_chemin);
        final int position = index;
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1 -> closeTab(file);
                case 2 -> closeOtherTabs(file);
                case 3 -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) requireContext()
                                    .getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            getString(R.string.atelier_onglet_copier_chemin),
                            file.toString()));
                    toast(getString(R.string.atelier_chemin_copie));
                }
                case 4 -> fermerCôté(position, 1);
                case 5 -> fermerCôté(position, -1);
                case 6 -> toutFermer();
                default -> { }
            }
            return true;
        });
        menu.show();
    }

    /**
     * Ferme tout ce qui est d'un côté de l'onglet (référence closeToRight/
     * closeToLeft) ; jamais de perte silencieuse — confirmation si modifiés.
     */
    private void fermerCôté(int position, int direction) {
        List<Path> cibles = new ArrayList<>();
        if (direction > 0) {
            for (int i = openTabs.size() - 1; i > position; i--) {
                cibles.add(openTabs.get(i));
            }
        } else {
            for (int i = 0; i < position && i < openTabs.size(); i++) {
                cibles.add(openTabs.get(i));
            }
        }
        fermerEnsemble(cibles);
    }

    /** Ferme tous les onglets ouverts (référence closeAll). */
    private void toutFermer() {
        fermerEnsemble(new ArrayList<>(openTabs));
    }

    /** Fermeture d'un ensemble : confirmation honnête si des tampons sont modifiés. */
    private void fermerEnsemble(List<Path> cibles) {
        long modifiés = cibles.stream()
                .filter(f -> modifiedBuffers.containsKey(f)
                        || (f.equals(activeFile) && currentDirty))
                .count();
        if (modifiés == 0) {
            for (Path file : cibles) {
                removeTab(file);
            }
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.atelier_onglet_autres_modifiees,
                        (int) modifiés))
                .setPositiveButton(R.string.atelier_onglet_ignorer,
                        (dialog, which) -> {
                            for (Path file : cibles) {
                                removeTab(file);
                            }
                        })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
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
        mémoriserRécent(file);
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
        // La session de langage LSP suit l'ouverture (java, kotlin, xml) :
        // complétion typée, survol, signatures, diagnostics — la porte
        // céleris connectServer (LOT-11, B-02).
        monterSessionLangage();
        // Un fichier texte s'ouvre : Code visible, états masqués (preview).
        showCodeView();
        refreshTools();
    }

    /**
     * Pose la session de langage du document actif (référence LspSessionMount
     * du sample céleris) : le montage (initialisation du serveur) se joue sur
     * le fil des serveurs, le branchement revient au fil de l'interface — une
     * pose dépassée (changement d'onglet pendant la poignée de main) ne se
     * branche jamais, le serveur du fichier A ne sert jamais le document B.
     */
    private void monterSessionLangage() {
        jo.joide.app.lsp.LspSessions sessions = sessionsLsp;
        if (sessions == null || editor == null || editor.session() == null
                || activeFile == null) {
            return;
        }
        String langage = langageLspDe(activeFile);
        if (langage == null) {
            return; // fichier sans moteur : pas de session, honnête.
        }
        final int pose = sessions.nouvellePose();
        final String uri = activeFile.toAbsolutePath().toUri().toString();
        final jo.celeris.core.text.TextSession textePose = editor.session();
        uriDocumentActif = uri;
        sessions.filServeurs().execute(() -> {
            if (!sessions.poseCourante(pose)) {
                return;
            }
            org.eclipse.lsp4j.services.LanguageServer serveur =
                    sessions.serveurInitialisé(langage);
            if (serveur == null || !sessions.poseCourante(pose)) {
                return;
            }
            jo.joide.app.lsp.EditorLanguageSession sessionÉdition =
                    new jo.joide.app.lsp.EditorLanguageSession(serveur, langage,
                            sessions.filServeurs(),
                            sessions.légendeJetons(langage));
            if (binding == null) {
                return;
            }
            binding.getRoot().post(() -> {
                if (binding == null || !sessions.poseCourante(pose)
                        || editor == null || editor.popups() == null
                        || editor.session() != textePose) {
                    return;
                }
                editor.popups().connectServer(sessionÉdition, uri,
                        editor.language());
            });
        });
    }

    /** L'identifiant de langage LSP d'un fichier (null : sans moteur). */
    private static String langageLspDe(java.nio.file.Path fichier) {
        String nom = fichier.getFileName().toString().toLowerCase(
                java.util.Locale.ROOT);
        if (nom.endsWith(".java")) {
            return "java";
        }
        if (nom.endsWith(".kt") || nom.endsWith(".kts")) {
            return "kotlin";
        }
        if (nom.endsWith(".xml")) {
            return "xml";
        }
        return null;
    }

    /**
     * Les diagnostics publiés par les moteurs pour le document ACTIF : la
     * conversion céleris posée aux popups — squiggles, gouttière, bande
     * d'erreurs (référence : les diagnostics de l'éditeur, même design).
     */
    private void recevoirDiagnosticsLsp(String uri,
            List<org.eclipse.lsp4j.Diagnostic> diags) {
        if (binding == null || editor == null || editor.popups() == null
                || uriDocumentActif == null || !uriDocumentActif.equals(uri)) {
            return;
        }
        diagnosticsLsp.clear();
        if (diags != null) {
            for (org.eclipse.lsp4j.Diagnostic diagnostic : diags) {
                if (diagnostic != null && diagnostic.getRange() != null) {
                    diagnosticsLsp.add(enDiagnosticHôte(uri, diagnostic));
                }
            }
        }
        editor.popups().setDiagnostics(List.copyOf(diagnosticsLsp));
    }

    /** Un diagnostic lsp4j en diagnostic d'hôte céleris (lignes 1-based). */
    private static jo.celeris.android.host.spi.HostDiagnostic enDiagnosticHôte(
            String uri, org.eclipse.lsp4j.Diagnostic diagnostic) {
        String chemin = uri;
        if (uri.startsWith("file://")) {
            chemin = uri.substring("file://".length());
        }
        int gravité = diagnostic.getSeverity() != null
                ? diagnostic.getSeverity().getValue() : 3;
        jo.celeris.android.host.spi.GraviteDiagnostic gravitéCéleris =
                gravité == 1 ? jo.celeris.android.host.spi.GraviteDiagnostic.ERREUR
                : gravité == 2
                        ? jo.celeris.android.host.spi.GraviteDiagnostic.AVERTISSEMENT
                        : jo.celeris.android.host.spi.GraviteDiagnostic.INFO;
        return new jo.celeris.android.host.spi.HostDiagnostic(
                jo.celeris.android.host.spi.CheminDoc.of(chemin),
                diagnostic.getRange().getStart().getLine() + 1,
                diagnostic.getRange().getStart().getCharacter() + 1,
                gravitéCéleris,
                diagnostic.getMessage() != null ? diagnostic.getMessage() : "");
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
            selection -> requireActivity().runOnUiThread(() -> {
                updateSymbolPath();
                màjFilArianeDifférée();
            });

    /** Fil (anti-rebond) du fil d'Ariane — 200 ms comme la référence. */
    private final android.os.Handler filHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Tâche anti-rebond en cours du fil d'Ariane. */
    private Runnable filTâche;

    // ---- Dock à 3 detents (preview bottom-sheet : 60dp réduit → 60 % mi-hauteur
    //      → plein calé sous la toolbar) ------------------------------------

    private com.google.android.material.bottomsheet.BottomSheetBehavior<
            android.view.View> dockBehavior;

    /** Console de build (modèle pur, testé) : lignes, tâches en place, capacité. */
    private final ConsoleBuffer consoleBuffer = new ConsoleBuffer(
            ConsoleBuffer.DEFAULT_CAPACITY);

    /** Tampon rendu (spans par nature de ligne) — reconstruit sur clear(). */
    private final SpannableStringBuilder consoleText = new SpannableStringBuilder();

    /** Plages par ligne (id → [début, fin) texte+saut) — ordre d'insertion. */
    private final Map<Long, long[]> consoleRanges = new LinkedHashMap<>();

    /** Plage de la note d'élagage (« … N lignes antérieures »), ou null. */
    private long[] consoleNoteRange;

    /** Rangées d'étapes par tâche (maj en place : ◐ devient ✔/✗). */
    private final Map<String, StepRow> stepRows = new HashMap<>();

    /** Une rangée d'étape vivante (marqueur + libellé, maj en place). */
    private static final class StepRow {
        final LinearLayout row;
        final TextView marker;
        final TextView label;

        StepRow(LinearLayout row, TextView marker, TextView label) {
            this.row = row;
            this.marker = marker;
            this.label = label;
        }
    }

    /** Compteurs réels du build courant (diagnostics par sévérité, tâches). */
    private int buildErrorCount;
    private int buildWarningCount;
    private int buildTaskCount;
    private int buildTasksTotal;

    /** Instant de fin du dernier build (âge relatif de la barre) et verdict. */
    private long lastBuildEnd;
    private boolean lastBuildSuccess;

    /** Build courant en cours (barre du dock en « ◐ build »). */
    private boolean buildRunning;

    /** Dernière progression vue (barre du dock « ◐ build · 3/12 »). */
    private int buildTasksDone;

    /** Console composée par le MOTEUR (Forge v0.2.26, protocole 4 — décision
     *  D-21) : les lignes reçues sont déjà rédigées et triées, l'atelier
     *  AFFICHE et ne compose plus ses lignes de tâches ni son verdict (rien ne
     *  double) ; faux face à un moteur plus ancien ou au mode Gradle (la
     *  composition locale d'avant reste la règle). */
    private volatile boolean consoleDuMoteur;

    /** Niveau de console choisi (chips Essentiel/Détaillé/Tout) : l'ARGUMENT
     *  envoyé au moteur avec la requête (l'équivalent des -q/-i/-d de Gradle —
     *  le moteur trie à l'émission) ET le seuil d'affichage local (un build
     *  lancé à un niveau ne peut que se RESSERRER en cours de route, jamais
     *  s'élargir : ce que le moteur n'a pas émis n'existe plus). */
    private BuildEvent.Log.Level niveauConsole = BuildEvent.Log.Level.LIFECYCLE;

    /** Chips du niveau (même ordre que {@link #niveauxChips}). */
    private final List<TextView> chipsNiveau = new ArrayList<>();

    /** Niveau porté par chaque chip (Essentiel=LIFECYCLE, Détaillé=INFO, Tout=DEBUG). */
    private final List<BuildEvent.Log.Level> niveauxChips = new ArrayList<>();

    /** Dernière pulsation reçue (barre vivante « ◐ tâche · 45 s »), ou null. */
    private BuildEvent.Heartbeat dernièrePulsation;

    /** Pied du résumé des problèmes (« 2 erreurs · 1 avertissement »). */
    private TextView problemsSummary;

    private void setupDock() {
        dockBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior
                .from(binding.dockSheet);
        // Plein : PLEIN ÉCRAN comme la référence (la console couvre tout,
        // seule la poignée — ou un glissé — replie).
        dockBehavior.setExpandedOffset(0);
        dockBehavior.setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior
                        .STATE_COLLAPSED);
        dockBehavior.addBottomSheetCallback(new com.google.android.material
                .bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View sheet, int newState) {
                // La barre rafraîchit son âge relatif quand la feuille
                // s'ouvre (« ▶ build ok · il y a 3 min », jamais figé).
                if (lastBuildEnd > 0) {
                    renderDockBuildPill();
                }
            }

            @Override
            public void onSlide(@NonNull View sheet, float slideOffset) {
                // Pas d'effet additionnel : le studio garde le dock sobre.
            }
        });
        // Ligne de progression fine sur le bord supérieur (référence BuildDock
        // : 2dp, accent ; déterminée si le total est connu, indéterminée sinon).
        binding.dockProgression.setProgressTintList(
                android.content.res.ColorStateList.valueOf(
                        getResources().getColor(R.color.primaire)));
        binding.dockProgression.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        // Clavier logiciel (référence : le dock se CACHE pendant la frappe,
        // la barre de symboles prend le slot du bas) — et le padding bas de
        // la colonne part avec lui : le dock replié ne masque JAMAIS le bas
        // de l'éditeur, clavier ou pas.
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent, (v, insets) -> {
            boolean clavier = insets.isVisible(WindowInsetsCompat.Type.ime());
            int replié = getResources().getDimensionPixelSize(R.dimen.dock_replie_hauteur);
            binding.colonnePrincipale.setPadding(0, 0, 0, clavier ? 0 : replié);
            binding.dockSheet.setVisibility(clavier ? View.GONE : View.VISIBLE);
            if (clavier && dockBehavior.getState() != com.google.android.material
                    .bottomsheet.BottomSheetBehavior.STATE_COLLAPSED) {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_COLLAPSED);
            }
            return insets;
        });

        // Toute la zone handle+barre déplie le dock (preview : la poignée ET
        // la barre d'état déplient) ; un build ÉCHOUÉ ouvre la feuille, un
        // build réussi ne vole jamais le focus.
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
        // Chips de niveau de console (Essentiel · Détaillé · Tout) : l'argument
        // du moteur, présent avant les onglets — la console est leur cible.
        setupConsoleFilter();
        // Onglets du mode actif, Console active (preview : 1er chip ON —
        // l'onglet des états vient AVANT les contenus).
        rebuildDockTabs();
        showDockSection(0);
        renderDockBuildPill();
    }

    /** Onglets du dock selon le mode (preview : Forge Console·Problèmes·Étapes·
     *  Journal·Logcat — Étapes ouvre le répertoire des tâches du moteur au repos,
     *  l'horloge des tâches en build ; Gradle Console·Problèmes·Étapes·Terminal) :
     *  chips 32dp. */
    private void rebuildDockTabs() {
        boolean gradle = activeMode() == WorkMode.GRADLE;
        int[] labels = gradle
                ? new int[]{R.string.section_console_gradle, R.string.section_problemes,
                        R.string.section_etapes, R.string.terminal_titre}
                : new int[]{R.string.section_console_forge, R.string.section_problemes,
                        R.string.section_etapes, R.string.section_journal,
                        R.string.section_logcat};
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

    /** Section visible du dock (studio : chips 32dp, ON = sc2) et contenus réels.
     *  Forge : Console·Problèmes·Étapes·Journal·Logcat (Étapes = répertoire du
     *  moteur au repos — D-22 —, horloge en build) ; Gradle : le terminal ouvre
     *  l'écran dédié en 4e. */
    private void showDockSection(int position) {
        // Sections dans l'ordre des onglets du mode courant (Gradle : Terminal
        // en 4e ouvre l'écran terminal ; Forge : Journal·Logcat en 4e·5e).
        boolean gradle = activeMode() == WorkMode.GRADLE;
        binding.problemsSection.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        binding.consoleSection.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        // Les chips de niveau vivent avec la console (elles la commandent).
        binding.consoleFilterRow.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        binding.journalSection.setVisibility(
                !gradle && position == 3 ? View.VISIBLE : View.GONE);
        binding.logcatSection.setVisibility(
                !gradle && position == 4 ? View.VISIBLE : View.GONE);
        // Étapes : rangées d'horloge des tâches du build (réelles) ; au repos en
        // mode Forge, le RÉPERTOIRE du moteur (protocole 5) — touche = exécution.
        binding.stepsSection.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        if (!gradle && position == 2 && !buildRunning) {
            chargerRépertoireSiVide();
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

    /** Copie la sortie console dans le presse-papiers (sortie réelle +
     *  note d'élagage — jamais un texte tronqué silencieusement). */
    private void copyConsole() {
        String note = consoleBuffer.trimmedTotal() > 0
                ? getString(R.string.atelier_console_masquees,
                        consoleBuffer.trimmedTotal()) + "\n"
                : "";
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                getString(R.string.section_console), note + consoleBuffer.plainText()));
        toast(getString(R.string.diagnostic_copie_ok));
    }

    // ---- Niveau de console (Essentiel · Détaillé · Tout — un ARGUMENT du
    //      moteur, comme les -q/-i/-d de Gradle : le moteur trie à l'émission,
    //      l'atelier demande ; le seuil local ne peut que resserrer) ---------

    /** Fichier de préférences du niveau de console (mémorisé entre les runs). */
    private static final String PRÉFÉRENCES_CONSOLE = "atelier_console";

    /** Clé du niveau mémorisé. */
    private static final String CLÉ_NIVEAU = "niveau";

    /** Construit les chips de niveau (studio : chips 28dp, ON = sc2). Le choix
     *  part avec la PROCHAINE requête de build ; le build courant ne peut que
     *  se resserrer à l'affichage. */
    private void setupConsoleFilter() {
        chargerNiveauConsole();
        BuildEvent.Log.Level[] niveaux = {
                BuildEvent.Log.Level.LIFECYCLE,
                BuildEvent.Log.Level.INFO,
                BuildEvent.Log.Level.DEBUG };
        int[] libellés = {
                R.string.atelier_console_filtre_essentiel,
                R.string.atelier_console_filtre_detaille,
                R.string.atelier_console_filtre_tout };
        chipsNiveau.clear();
        niveauxChips.clear();
        binding.consoleFilterRow.removeAllViews();
        for (int i = 0; i < niveaux.length; i++) {
            final BuildEvent.Log.Level niveau = niveaux[i];
            TextView chip = new TextView(requireContext());
            chip.setText(getString(libellés[i]));
            chip.setTextSize(12.5f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dip(14), 0, dip(14), 0);
            chip.setContentDescription(getString(R.string.atelier_console_filtre_cd)
                    + " : " + getString(libellés[i]));
            chip.setOnClickListener(v -> applyConsoleLevel(niveau));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(28));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chipsNiveau.add(chip);
            niveauxChips.add(niveau);
            binding.consoleFilterRow.addView(chip);
        }
        styliserChipsNiveau();
    }

    /** Applique le niveau choisi : mémorisé (prochaine requête) et seuil
     *  d'affichage du build courant (resserré, jamais élargi). */
    private void applyConsoleLevel(BuildEvent.Log.Level niveau) {
        niveauConsole = niveau;
        requireContext().getSharedPreferences(PRÉFÉRENCES_CONSOLE, Context.MODE_PRIVATE)
                .edit().putString(CLÉ_NIVEAU, niveau.name()).apply();
        styliserChipsNiveau();
    }

    /** Charge le niveau mémorisé (défaut : LIFECYCLE, la console de build
     *  classique — lignes de tâches, verdict, avertissements, erreurs). */
    private void chargerNiveauConsole() {
        String mémorisé = requireContext()
                .getSharedPreferences(PRÉFÉRENCES_CONSOLE, Context.MODE_PRIVATE)
                .getString(CLÉ_NIVEAU, BuildEvent.Log.Level.LIFECYCLE.name());
        try {
            niveauConsole = BuildEvent.Log.Level.valueOf(mémorisé);
        } catch (IllegalArgumentException inconnu) {
            niveauConsole = BuildEvent.Log.Level.LIFECYCLE;
        }
    }

    /** Une entrée de ce niveau s'affiche-t-elle ? (L'ordinal suit la sévérité :
     *  DEBUG &lt; INFO &lt; LIFECYCLE &lt; WARNING &lt; ERROR — le seuil ne retient
     *  que plus grave. Le moteur a déjà appliqué le niveau demandé à
     *  l'émission ; ceci ne peut que RESSERRER l'affichage.) */
    private boolean niveauAffiché(BuildEvent.Log.Level niveau) {
        return niveau.ordinal() >= niveauConsole.ordinal();
    }

    /** Style des chips : la sélection prend le fond sc2 du studio. */
    private void styliserChipsNiveau() {
        for (int i = 0; i < chipsNiveau.size(); i++) {
            TextView chip = chipsNiveau.get(i);
            boolean on = niveauxChips.get(i) == niveauConsole;
            chip.setBackground(getResources().getDrawable(
                    on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    // ---- Rendu de la console (preview bottom-sheet : lignes colorées par
    //      nature, maj en place, auto-suivi sauf remontée manuelle) ----------

    /** Rejoue les mutations du tampon sur le Spannable et rafraîchit la vue. */
    private void renderConsole() {
        List<ConsoleBuffer.Mutation> mutations = consoleBuffer.drain();
        if (mutations.isEmpty()) {
            return;
        }
        boolean follow = consoleAtBottom();
        for (ConsoleBuffer.Mutation mutation : mutations) {
            switch (mutation.type) {
                case APPEND -> appendConsoleLine(mutation.entry);
                case UPDATE -> updateConsoleLine(mutation.entry);
                case TRIM -> trimConsoleLines(mutation.count);
            }
        }
        renderConsoleNote();
        binding.consoleOutput.setText(null);
        binding.consoleOutput.setText(consoleText);
        if (follow) {
            binding.consoleSection.post(() ->
                    binding.consoleSection.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** Ajoute une ligne stylée (couleur de nature, gras des bilans). */
    private void appendConsoleLine(ConsoleBuffer.Entry entry) {
        int start = consoleText.length();
        consoleText.append(entry.text).append('\n');
        styleConsoleLine(entry, start, start + entry.text.length());
        consoleRanges.put(entry.id, new long[]{start, consoleText.length()});
    }

    /** Remplace une ligne en place (tâche ◐ → ✔, progression, bilan) et
     *  décale les plages suivantes du delta de longueur. */
    private void updateConsoleLine(ConsoleBuffer.Entry entry) {
        long[] range = consoleRanges.get(entry.id);
        if (range == null) {
            appendConsoleLine(entry);
            return;
        }
        int start = (int) range[0];
        int oldEnd = (int) range[1];
        String replacement = entry.text + "\n";
        consoleText.replace(start, oldEnd, replacement);
        styleConsoleLine(entry, start, start + entry.text.length());
        int newEnd = start + replacement.length();
        int delta = newEnd - oldEnd;
        boolean passed = false;
        for (var e : consoleRanges.entrySet()) {
            if (passed) {
                e.getValue()[0] += delta;
                e.getValue()[1] += delta;
            }
            if (e.getKey() == entry.id) {
                e.getValue()[1] = newEnd;
                passed = true;
            }
        }
    }

    /** Supprime les n plus anciennes lignes (capacité bornée du tampon) —
     *  après la note d'élagage, jamais à travers elle. */
    private void trimConsoleLines(int count) {
        int removed = 0;
        var iterator = consoleRanges.entrySet().iterator();
        for (int i = 0; i < count && iterator.hasNext(); i++) {
            long[] range = iterator.next().getValue();
            removed += range[1] - range[0];
            iterator.remove();
        }
        if (removed > 0) {
            int from = consoleNoteRange != null ? (int) consoleNoteRange[1] : 0;
            consoleText.delete(from, from + removed);
            for (long[] range : consoleRanges.values()) {
                range[0] -= removed;
                range[1] -= removed;
            }
        }
    }

    /** Note d'élagage en tête (« … N lignes antérieures masquées ») : créée
     *  au premier élagage, puis son texte est remplacé en place. */
    private void renderConsoleNote() {
        int trimmed = consoleBuffer.trimmedTotal();
        if (trimmed == 0) {
            return;
        }
        String note = getString(R.string.atelier_console_masquees, trimmed) + "\n";
        if (consoleNoteRange == null) {
            consoleText.insert(0, note);
            for (long[] range : consoleRanges.values()) {
                range[0] += note.length();
                range[1] += note.length();
            }
        } else {
            int delta = note.length() - (int) (consoleNoteRange[1] - consoleNoteRange[0]);
            consoleText.replace((int) consoleNoteRange[0],
                    (int) consoleNoteRange[1], note);
            if (delta != 0) {
                for (long[] range : consoleRanges.values()) {
                    range[0] += delta;
                    range[1] += delta;
                }
            }
        }
        consoleNoteRange = new long[]{0, note.length()};
        consoleText.setSpan(new ForegroundColorSpan(
                        getResources().getColor(R.color.contour)),
                0, note.length() - 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Couleur + graisse d'une ligne selon sa nature (jetons du studio). */
    private void styleConsoleLine(ConsoleBuffer.Entry entry, int start, int end) {
        int color;
        switch (entry.kind) {
            case TASK_OK, SUCCESS -> color = R.color.ok;
            case TASK_FAILED, FAILURE, ERROR -> color = R.color.erreur;
            case WARNING -> color = R.color.alerte;
            case TASK_RUNNING, HEADER, INFO -> color = R.color.info;
            default -> color = R.color.sur_var;
        }
        consoleText.setSpan(new ForegroundColorSpan(getResources().getColor(color)),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (entry.kind == ConsoleBuffer.Kind.SUCCESS
                || entry.kind == ConsoleBuffer.Kind.FAILURE
                || entry.kind == ConsoleBuffer.Kind.HEADER) {
            consoleText.setSpan(new StyleSpan(Typeface.BOLD),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** L'utilisateur est-il en bas de la sortie ? (sinon : ne pas le tirer) */
    private boolean consoleAtBottom() {
        View child = binding.consoleSection.getChildAt(0);
        return child == null || binding.consoleSection.getScrollY()
                >= child.getHeight() - binding.consoleSection.getHeight() - dip(24);
    }

    /** Réinitialise le rendu après un clear() du modèle (nouveau build). */
    private void resetConsoleView() {
        consoleText.clear();
        consoleRanges.clear();
        consoleNoteRange = null;
        binding.consoleOutput.setText("");
    }

    /** Une rangée d'étape (preview : ✔/◐/✗ + libellé), maj en place ensuite. */
    private StepRow stepRow(String marker, String text, int markerColorRes,
            int textColorRes) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView markerView = new TextView(requireContext());
        markerView.setText(marker);
        markerView.setTextColor(getResources().getColor(markerColorRes));
        markerView.setTextSize(11.5f);
        row.addView(markerView, new LinearLayout.LayoutParams(
                dip(16), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextColor(getResources().getColor(textColorRes));
        label.setTextSize(11.5f);
        label.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dip(8);
        label.setLayoutParams(params);
        row.addView(label);
        return new StepRow(row, markerView, label);
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

    // ---- Indexation à l'ouverture (lot L10) ----------------------------------

    /** L'index du projet ouvert (null : projet inexistant). */
    private jo.joide.index.IndexService index;

    /** Fil de fond unique de l'index (basse priorité : daemon). */
    private final java.util.concurrent.ExecutorService indexExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread fil = new Thread(runnable, "joide-index");
                fil.setDaemon(true);
                fil.setPriority(Thread.NORM_PRIORITY - 1);
                return fil;
            });

    /** Sémaphore « une compile tourne » : l'index CÈDE au build (jamais l'inverse). */
    private final jo.joide.workbench.BuildActivity buildActivity =
            new jo.joide.workbench.BuildActivity();

    /** Ouvre l'index du projet et lance la première passe (fil de fond). */
    private void démarrerIndexation() {
        try {
            index = new jo.joide.index.IndexService(
                    java.nio.file.Paths.get(projectPath));
        } catch (RuntimeException racineInvalide) {
            démarrerLsp();
            return; // projet illisible : pas d'index local, le LSP reste.
        }
        // L'index local (L10) nourrit les SYMBOLES DU TIROIR : son verdict
        // part au journal/console/avis — la PUCE, elle, suit l'index des
        // MOTeurs LSP (LOT-11) : c'est le pass réel des bibliothèques et du
        // SDK, celui que la référence montre à l'ouverture.
        index.observe(statut -> {
            if (!statut.building() && binding != null) {
                binding.getRoot().post(() -> {
                    if (binding == null || statut.stats().ms() <= 0) {
                        return;
                    }
                    // Avis honnête si la passe s'est faite couper (référence).
                    indexInterrompu = !statut.ready();
                    rendreAvis();
                    consoleBuffer.line(ConsoleBuffer.Kind.INFO,
                            statut.ready()
                                    ? getString(R.string.atelier_index_pret,
                                            statut.stats().symboles(),
                                            statut.stats().classes(),
                                            statut.stats().fichiersRéutilisés())
                                    : getString(R.string.atelier_index_interrompu));
                    renderConsole();
                    journalActivité().record(
                            jo.joide.corestore.ActivityLog.Kind.INDEX,
                            statut.ready()
                                    ? getString(R.string.activite_index_pret,
                                            statut.stats().symboles(),
                                            statut.stats().classes())
                                    : getString(R.string.atelier_index_interrompu),
                            null);
                });
            }
        });
        relancerIndexation();
        démarrerLsp();
    }

    /**
     * Monte les sessions LSP du projet (LOT-11) : moteurs Java, Kotlin et
     * XML de la brique code-lsp v1.15.0 EN PROCESSUS, nourris du classpath
     * Forge réel. Le pass d'index qui suit la sync est RÉEL — la phase
     * « Bibliothèques et SDK » scanne android.jar et les jars résolus —
     * c'est l'écran d'indexation de la référence qui s'affiche enfin.
     */
    private void démarrerLsp() {
        if (projectPath == null) {
            return;
        }
        final android.content.Context contexte = requireContext();
        Thread montage = new Thread(() -> {
            jo.joide.app.lsp.LspSessions sessions =
                    jo.joide.app.lsp.LspSessions.open(contexte,
                            java.nio.file.Paths.get(projectPath),
                            androidJarDuProjet(contexte));
            if (sessions == null) {
                return; // honnête : l'atelier vit sans LSP, le logcat le dit.
            }
            sessionsLsp = sessions;
            // Les diagnostics publiés par les moteurs (debounce 50 ms côté
            // serveur) rejoignent l'éditeur : squiggles + bande d'erreurs.
            retraitDiagnosticsLsp = sessions.pont().onDiagnostics(
                    (uri, diags) -> {
                        if (binding != null) {
                            binding.getRoot().post(() ->
                                    recevoirDiagnosticsLsp(uri, diags));
                        }
                    });
            sessions.syncForge();
            if (binding != null) {
                binding.getRoot().post(this::démarrerTickIndex);
            }
        }, "joide-lsp-montage");
        montage.setDaemon(true);
        montage.start();
    }

    /**
     * Le jar de la plateforme Android du projet : d'abord les composants
     * installés du moteur Forge (filesDir/forge/components/platform-N),
     * puis le SDK local des machines de développement (ANDROID_HOME) —
     * null si absent (honnête : l'index scanne alors les seules sources).
     */
    private static java.nio.file.Path androidJarDuProjet(
            android.content.Context contexte) {
        try {
            java.nio.file.Path composants = contexte.getFilesDir().toPath()
                    .resolve("forge").resolve("components");
            java.nio.file.Path trouvé = premierJarPlateforme(composants);
            if (trouvé != null) {
                return trouvé;
            }
            String sdk = System.getenv("ANDROID_HOME");
            if (sdk != null && !sdk.isBlank()) {
                return premierJarPlateforme(
                        java.nio.file.Path.of(sdk).resolve("platforms"));
            }
        } catch (RuntimeException lecture) {
            // lecture impossible : pas de plateforme — honnête.
        }
        return null;
    }

    /** Le android.jar de plus haut niveau API sous un répertoire de plateformes. */
    private static java.nio.file.Path premierJarPlateforme(
            java.nio.file.Path racine) {
        try (var dossiers = java.nio.file.Files.list(racine)) {
            return dossiers.filter(java.nio.file.Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith("platform")
                            || d.getFileName().toString().startsWith("android-"))
                    .sorted(java.util.Comparator.<
                            java.nio.file.Path>comparingInt(d -> niveauApi(d)))
                    .map(d -> d.resolve("android.jar"))
                    .filter(java.nio.file.Files::isRegularFile)
                    .reduce((premier, dernier) -> dernier)
                    .orElse(null);
        } catch (java.io.IOException absent) {
            return null;
        }
    }

    /** Le niveau API d'un répertoire de plateforme (0 si illisible). */
    private static int niveauApi(java.nio.file.Path plateforme) {
        String nom = plateforme.getFileName().toString();
        String chiffres = nom.replaceAll("[^0-9]", "");
        try {
            return chiffres.isEmpty() ? 0 : Integer.parseInt(chiffres);
        } catch (NumberFormatException tropGrand) {
            return 0;
        }
    }

    /** Démarre le tick d'état d'index (250 ms — la puce et le dialogue suivent). */
    private void démarrerTickIndex() {
        if (!tickIndexActif) {
            tickIndexActif = true;
            reposIndex = 0;
            tickIndex.post(this::tickÉtatIndex);
        } else {
            reposIndex = 0;
        }
    }

    /** Un tick : lit l'état LSP, rafraîchit la puce et le dialogue, s'endort
     *  ~2 s après la fin d'un pass (la batterie reste à l'édition). */
    private void tickÉtatIndex() {
        if (binding == null) {
            tickIndexActif = false;
            return;
        }
        jo.joide.app.lsp.LspSessions sessions = sessionsLsp;
        if (sessions == null) {
            tickIndexActif = false;
            return;
        }
        jo.codeide.lsp.host.EtatIndexation état = sessions.étatIndexation();
        màjPuceIndex(état);
        màjDialogueIndex(état);
        direTransitionsIndex(état);
        if (état.getPhase() == jo.codeide.lsp.host.PhaseIndexation.CONSTRUCTION) {
            reposIndex = 0;
        } else if (++reposIndex > 8) {
            tickIndexActif = false;
            return;
        }
        tickIndex.postDelayed(this::tickÉtatIndex, 250);
    }

    /** Les transitions de phase se disent : console + journal d'activité. */
    private void direTransitionsIndex(jo.codeide.lsp.host.EtatIndexation état) {
        jo.codeide.lsp.host.PhaseIndexation phase = état.getPhase();
        if (phase == dernièrePhaseIndex) {
            return;
        }
        boolean première = dernièrePhaseIndex == null;
        dernièrePhaseIndex = phase;
        if (phase == jo.codeide.lsp.host.PhaseIndexation.CONSTRUCTION) {
            consoleBuffer.line(ConsoleBuffer.Kind.INFO,
                    getString(R.string.atelier_index_lance));
            renderConsole();
        } else if (!première
                && phase == jo.codeide.lsp.host.PhaseIndexation.PRET
                && état.getDureesPhases() != null) {
            consoleBuffer.line(ConsoleBuffer.Kind.INFO,
                    getString(R.string.atelier_index_pret_lsp,
                            état.getDureesPhases().getBibliothequesMs(),
                            état.getDureesPhases().getSourcesMs()));
            renderConsole();
            journalActivité().record(
                    jo.joide.corestore.ActivityLog.Kind.INDEX,
                    getString(R.string.atelier_index_pret_lsp,
                            état.getDureesPhases().getBibliothequesMs(),
                            état.getDureesPhases().getSourcesMs()),
                    null);
        }
    }

    /**
     * Puce d'indexation (référence IndexStatusChip, barre compacte) : visible
     * seulement pendant un pass — spinner + pourcentage — puis disparaît.
     * Pilotée par l'état LSP (le pass réel des moteurs, LOT-11).
     */
    private void màjPuceIndex(jo.codeide.lsp.host.EtatIndexation état) {
        if (binding == null) {
            return;
        }
        if (état.getPhase() != jo.codeide.lsp.host.PhaseIndexation.CONSTRUCTION) {
            binding.indexChip.setVisibility(View.GONE);
            return;
        }
        binding.indexChip.setVisibility(View.VISIBLE);
        int total = état.getTotal();
        if (total > 0) {
            binding.indexChipTexte.setText(getString(R.string.atelier_index_pourcent,
                    (int) Math.round(état.getFaits() * 100.0 / total)));
        } else {
            binding.indexChipTexte.setText(R.string.atelier_index_en_cours);
        }
    }

    /**
     * Détail de l'index (référence IndexStatusDialog, 1:1) : en-tête (phase,
     * compte), barre de progression, liste de travail avec états, répartition
     * des indexeurs, durées, cache — et le bouton Réindexer.
     */
    private void montrerDétailIndex() {
        jo.joide.app.lsp.LspSessions sessions = sessionsLsp;
        if (sessions == null) {
            return;
        }
        android.view.LayoutInflater gonfleur = android.view.LayoutInflater
                .from(requireContext());
        android.view.View vue = gonfleur.inflate(R.layout.dialog_index_status,
                null);
        dialogueIndexCorps = vue;
        dialogueIndexBarre = vue.findViewById(R.id.index_dialog_bar);
        dialogueIndexCompte = vue.findViewById(R.id.index_dialog_count);
        dialogueIndexSous = vue.findViewById(R.id.index_dialog_sub);
        dialogueIndexListe = vue.findViewById(R.id.index_dialog_work_list);
        dialogueIndexIndexeurs = vue.findViewById(R.id.index_dialog_indexers);
        dialogueIndexDurées = vue.findViewById(R.id.index_dialog_durations);
        dialogueIndexCache = vue.findViewById(R.id.index_dialog_cache);
        vue.findViewById(R.id.index_dialog_close).setOnClickListener(v ->
                fermerDialogueIndex());
        vue.findViewById(R.id.index_dialog_reindex).setOnClickListener(v -> {
            sessions.réindexer();
            démarrerTickIndex();
        });
        dialogueIndex = new android.app.Dialog(requireContext());
        dialogueIndex.setContentView(vue);
        android.view.Window fenêtre = dialogueIndex.getWindow();
        if (fenêtre != null) {
            fenêtre.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
        }
        dialogueIndex.setOnDismissListener(d -> {
            dialogueIndex = null;
            dialogueIndexCorps = null;
        });
        dialogueIndex.show();
        màjDialogueIndex(sessions.étatIndexation());
        démarrerTickIndex();
    }

    /** Ferme le dialogue d'état d'index (ses vues vives partent avec). */
    private void fermerDialogueIndex() {
        if (dialogueIndex != null) {
            dialogueIndex.dismiss();
        }
    }

    /** Rafraîchit le dialogue ouvert par l'état LSP courant (rien si fermé). */
    private void màjDialogueIndex(jo.codeide.lsp.host.EtatIndexation état) {
        android.view.View vue = dialogueIndexCorps;
        if (vue == null || dialogueIndex == null) {
            return;
        }
        boolean construction =
                état.getPhase() == jo.codeide.lsp.host.PhaseIndexation.CONSTRUCTION;
        android.widget.ImageView icône = vue.findViewById(R.id.index_dialog_icon);
        android.widget.TextView titre = vue.findViewById(R.id.index_dialog_title);
        icône.setImageTintList(android.content.res.ColorStateList.valueOf(
                construction ? couleur(R.color.primaire) : couleur(R.color.ok)));
        titre.setText(construction
                ? R.string.atelier_index_dialogue_titre_en_cours
                : R.string.atelier_index_dialogue_titre_repos);
        int libelléPhase;
        switch (état.getPhaseDetaillee()) {
            case BIBLIOTHEQUES ->
                    libelléPhase = R.string.atelier_index_dialogue_sous_bibliotheques;
            case SOURCES ->
                    libelléPhase = R.string.atelier_index_dialogue_sous_sources;
            default -> libelléPhase = construction
                    ? R.string.atelier_index_dialogue_en_travail
                    : R.string.atelier_index_dialogue_a_jour;
        }
        dialogueIndexSous.setText(libelléPhase);
        vue.findViewById(R.id.index_dialog_building).setVisibility(
                construction ? View.VISIBLE : View.GONE);
        vue.findViewById(R.id.index_dialog_idle).setVisibility(
                construction ? View.GONE : View.VISIBLE);
        if (construction) {
            int total = état.getTotal();
            if (total > 0) {
                dialogueIndexBarre.setIndeterminate(false);
                dialogueIndexBarre.setMax(total);
                dialogueIndexBarre.setProgress(Math.min(état.getFaits(), total));
            } else {
                dialogueIndexBarre.setIndeterminate(true);
            }
            boolean sources =
                    état.getPhaseDetaillee() == jo.codeide.lsp.host.PhaseDetailleeIndexation.SOURCES;
            dialogueIndexCompte.setText(getString(sources
                    ? R.string.atelier_index_dialogue_compte_fichiers
                    : R.string.atelier_index_dialogue_compte_artefacts,
                    état.getFaits(), Math.max(état.getFaits(), total)));
            android.widget.TextView entête = vue.findViewById(
                    R.id.index_dialog_work_header);
            entête.setText(sources
                    ? R.string.atelier_index_dialogue_entete_fichier_courant
                    : R.string.atelier_index_dialogue_entete_artefacts);
            remplirListeTravail(état);
        }
        remplirRépartition(état);
    }

    /** La liste de travail du dialogue (8 entrées visibles, +N au-delà). */
    private void remplirListeTravail(jo.codeide.lsp.host.EtatIndexation état) {
        dialogueIndexListe.removeAllViews();
        List<jo.codeide.lsp.host.EntreeTravailIndexation> entrées =
                état.getListeTravail();
        if (entrées == null || entrées.isEmpty()) {
            if (état.getEntreeCourante() != null) {
                entrées = List.of(new jo.codeide.lsp.host.EntreeTravailIndexation(
                        état.getEntreeCourante(),
                        jo.codeide.lsp.host.EtatEntreeTravailIndexation.ACTIF));
            }
        }
        if (entrées == null || entrées.isEmpty()) {
            return;
        }
        android.content.Context contexte = dialogueIndexListe.getContext();
        int bornes = Math.min(entrées.size(), 8);
        for (int i = 0; i < bornes; i++) {
            dialogueIndexListe.addView(
                    rangéeTravail(contexte, entrées.get(i)));
        }
        if (entrées.size() > bornes) {
            android.widget.TextView reste = new android.widget.TextView(contexte);
            reste.setText(getString(R.string.atelier_index_dialogue_entree_plus,
                    entrées.size() - bornes));
            reste.setTextColor(couleur(R.color.sur_var));
            reste.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            dialogueIndexListe.addView(reste);
        }
    }

    /** Une rangée de la liste de travail : l'état (fait/actif/en attente) + le nom. */
    private android.view.View rangéeTravail(android.content.Context contexte,
            jo.codeide.lsp.host.EntreeTravailIndexation entrée) {
        android.widget.LinearLayout rangée = new android.widget.LinearLayout(
                contexte);
        rangée.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        rangée.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.FrameLayout icône = new android.widget.FrameLayout(contexte);
        android.widget.LinearLayout.LayoutParams marge =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        marge.setMargins(0, 2, 10, 2);
        icône.setLayoutParams(marge);
        android.widget.TextView nom = new android.widget.TextView(contexte);
        nom.setText(entrée.getNom());
        nom.setTextColor(couleur(R.color.sur_var));
        nom.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        nom.setSingleLine(true);
        nom.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nom.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        switch (entrée.getEtat()) {
            case TERMINE, REPRIS, ECARTE -> {
                android.widget.ImageView fait = new android.widget.ImageView(
                        contexte);
                fait.setImageResource(R.drawable.ic_check);
                fait.setImageTintList(android.content.res.ColorStateList.valueOf(
                        entrée.getEtat()
                                == jo.codeide.lsp.host.EtatEntreeTravailIndexation.ECARTE
                                ? couleur(R.color.sur_var) : couleur(R.color.ok)));
                fait.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                icône.addView(fait);
            }
            case ACTIF -> {
                android.widget.ProgressBar tourne =
                        new android.widget.ProgressBar(contexte, null,
                                android.R.attr.progressBarStyleSmall);
                tourne.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                        14, 14));
                icône.addView(tourne);
            }
            default -> {
                android.widget.ImageView point = new android.widget.ImageView(
                        contexte);
                point.setImageResource(R.drawable.ic_point);
                point.setImageTintList(android.content.res.ColorStateList.valueOf(
                        couleur(R.color.sur_var)));
                point.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                icône.addView(point);
            }
        }
        rangée.addView(icône);
        rangée.addView(nom);
        return rangée;
    }

    /** La répartition du dialogue : indexeurs coûteux, durées, cache. */
    private void remplirRépartition(jo.codeide.lsp.host.EtatIndexation état) {
        List<jo.codeide.lsp.host.BreakdownIndexeurIndexation> répartition =
                état.getBreakdownIndexeurs();
        jo.codeide.lsp.host.BilanDernierPassageIndexation bilan =
                état.getBilanDernierPassage();
        boolean visible = (répartition != null && répartition.stream()
                .anyMatch(indexeur -> indexeur.getMillisecondesCumulees() >= 1))
                || état.getDureesPhases() != null;
        dialogueIndexCorps.findViewById(R.id.index_dialog_breakdown)
                .setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        dialogueIndexIndexeurs.removeAllViews();
        android.content.Context contexte = dialogueIndexIndexeurs.getContext();
        if (répartition != null) {
            for (jo.codeide.lsp.host.BreakdownIndexeurIndexation indexeur
                    : répartition) {
                if (indexeur.getMillisecondesCumulees() < 1) {
                    continue; // seuls les indexeurs qui ont coûté (référence)
                }
                android.widget.TextView ligne = new android.widget.TextView(
                        contexte);
                ligne.setText(getString(R.string.atelier_index_dialogue_indexeur,
                        indexeur.getLibelle() != null ? indexeur.getLibelle()
                                : indexeur.getIdentifiant(),
                        indexeur.getNombreEntrees(),
                        indexeur.getMillisecondesCumulees()));
                ligne.setTextColor(couleur(R.color.sur_var));
                ligne.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                dialogueIndexIndexeurs.addView(ligne);
            }
        }
        if (état.getDureesPhases() != null) {
            dialogueIndexDurées.setText(getString(
                    R.string.atelier_index_dialogue_phases,
                    état.getDureesPhases().getBibliothequesMs(),
                    état.getDureesPhases().getSourcesMs()));
        }
        if (bilan != null) {
            dialogueIndexCache.setText(getString(
                    R.string.atelier_index_dialogue_entrees_cache,
                    bilan.getArtefactsTotal(), bilan.getArtefactsRepris()));
        }
    }

    /** Une couleur du thème (raccourci honnête des longues lignes). */
    private int couleur(int idCouleur) {
        return androidx.core.content.ContextCompat.getColor(requireContext(),
                idCouleur);
    }

    /** Une passe d'index sur le périmètre RÉEL du projet (L10). */
    private void relancerIndexation() {
        if (index == null || projectPath == null) {
            return;
        }
        index.ensureUpToDate(
                jo.joide.index.IndexScope.duProjet(java.nio.file.Paths.get(projectPath)),
                indexExecutor, buildActivity);
    }

    @Nullable
    @Override
    public jo.joide.index.IndexService indexService() {
        return index;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // L'index survit (persistant) ; le fil de fond s'arrête proprement.
        indexExecutor.shutdownNow();
        // Les sessions LSP s'arrêtent sur leur fil (jamais d'ANR à la fermeture) ;
        // le tick s'endort, le dialogue et l'auditeur de diagnostics partent.
        tickIndex.removeCallbacksAndMessages(null);
        tickIndexActif = false;
        fermerDialogueIndex();
        if (retraitDiagnosticsLsp != null) {
            retraitDiagnosticsLsp.run();
            retraitDiagnosticsLsp = null;
        }
        jo.joide.app.lsp.LspSessions sessions = sessionsLsp;
        if (sessions != null) {
            sessionsLsp = null;
            Thread arrêt = new Thread(sessions::close, "joide-lsp-arret");
            arrêt.setDaemon(true);
            arrêt.start();
        }
        binding = null;
    }

    /** Mode actif (conception §4 : capacités honnêtes, jamais de bouton que le mode ne satisfait pas). */
    private WorkMode activeMode() {
        ModePreferences preferences = new ModePreferences(
                ModeSetupFragment.preferencesFile(requireContext()));
        jo.joide.corestore.ModeChoice choice = preferences.read();
        return choice != null && choice.chosenMode() != null
                ? choice.chosenMode() : WorkMode.FORGE;
    }

    /** Le build suit le mode actif : Forge par le moteur, Gradle dans le terminal.
     *  Cible vide : tout le graphe ; cible nommée : la tâche demandée (protocole 5). */
    private void runBuild() {
        runBuild(List.of());
    }

    /** Build ciblé (une tâche du répertoire du moteur — D-22) ou complet. */
    private void runBuild(List<String> cible) {
        // Nouveau build : les diagnostics rendus à l'agent repartent de zéro.
        lastDiagnostics.clear();
        lastStructuredDiagnostics.clear();
        beginBuildInDock();
        // Le sémaphore se lève : l'indexation de fond CÈDE (L10).
        buildActivity.set(true);
        journalActivité().record(jo.joide.corestore.ActivityLog.Kind.BUILD,
                getString(R.string.activite_build_lance,
                        activeMode() == WorkMode.FORGE
                                ? getString(R.string.mode_forge_nom)
                                : getString(R.string.mode_gradle_nom)),
                cible.isEmpty() ? null : String.join(" ", cible));
        if (activeMode() == WorkMode.GRADLE) {
            runGradleBuild();
        } else {
            runForgeBuild(cible);
        }
    }

    /** Départ de build : console, étapes et problèmes repartent propres,
     *  compteurs à zéro, barre du dock en « en cours » (preview). */
    private void beginBuildInDock() {
        consoleBuffer.clear();
        resetConsoleView();
        binding.stepsList.removeAllViews();
        stepRows.clear();
        binding.problemsList.removeAllViews();
        problemsSummary = null;
        buildErrorCount = 0;
        buildWarningCount = 0;
        buildTaskCount = 0;
        buildTasksTotal = 0;
        buildTasksDone = 0;
        buildRunning = true;
        // Console inconnue jusqu'à preuve du contraire : la composition locale
        // reste la règle ; le moteur protocole 4 la remplace à la session.
        consoleDuMoteur = false;
        dernièrePulsation = null;
        binding.buildStateOk.setVisibility(View.GONE);
        binding.buildStateErr.setVisibility(View.GONE);
        binding.buildStateWarn.setVisibility(View.GONE);
        // Fine ligne de progression sur le bord du dock (référence) + indice
        // d'apprentissage : le premier build avec le dock replié fait sauter
        // la barre (une fois, persisté) — le geste se découvre tout seul.
        binding.dockProgression.setVisibility(View.VISIBLE);
        binding.dockProgression.setIndeterminate(true);
        montrerIndiceDock();
        renderDockBuildPill();
    }

    /**
     * Indice du dock (référence BuildDock.hint) : une seule fois, si le dock
     * est replié, la barre monte de 18dp et retombe — le geste se voit.
     */
    private void montrerIndiceDock() {
        android.content.SharedPreferences préfs = requireContext()
                .getSharedPreferences("joide.atelier", android.content.Context.MODE_PRIVATE);
        if (préfs.getBoolean("dock_indice_vu", false)) {
            return;
        }
        if (dockBehavior.getState() != com.google.android.material.bottomsheet
                .BottomSheetBehavior.STATE_COLLAPSED) {
            return;
        }
        préfs.edit().putBoolean("dock_indice_vu", true).apply();
        android.animation.ValueAnimator saut =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        saut.setDuration(540);
        saut.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            // Montée douce, redescente amortie (220/320 ms comme la référence).
            float montée = Math.min(1f, t / 0.4f);
            float redescente = Math.max(0f, (t - 0.4f) / 0.6f);
            float hauteur = (montée - montée * redescente) * dip(18);
            binding.dockSheet.setTranslationY(-hauteur);
        });
        saut.start();
    }

    private void runGradleBuild() {
        if (gradleBackend == null) {
            gradleBackend = new GradleBackend();
        }
        String buildId = "joide-" + System.currentTimeMillis();
        consoleBuffer.header(getString(R.string.atelier_console_entete,
                getString(R.string.mode_gradle_nom), heure()));
        renderConsole();
        gradleBackend.build(new BuildRequest(buildId, projectPath, false))
                .whenComplete((session, failure) -> {
                    if (failure != null) {
                        onBackendLaunchFailure(failure);
                    } else if (session != null) {
                        session.subscribe(this::onBuildEvent);
                    }
                });
    }

    /** Fabrique (une fois) le backend Forge avec le profil RÉEL de l'appareil. */
    private void assurerForgeBackend() {
        if (forgeBackend != null) {
            return;
        }
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
        // Réglages du runtime (page Runtime de build — L10) : lus du fichier de
        // préférences réel, appliqués à CE lancement du moteur.
        forgeBackend.configure(new jo.joide.buildforge.EngineRuntimePrefs(
                requireContext().getFilesDir().toPath()
                        .resolve("runtime.properties")).read());
    }

    private void runForgeBuild(List<String> cible) {
        assurerForgeBackend();
        String buildId = "joide-" + System.currentTimeMillis();
        consoleBuffer.header(getString(R.string.atelier_console_entete,
                getString(R.string.mode_forge_nom), heure())
                + (cible.isEmpty() ? "" : " · " + getString(R.string.atelier_console_cible,
                        String.join(" ", cible))));
        renderConsole();
        // Le niveau de console part AVEC la requête : un argument du moteur
        // (l'équivalent des -q/-i/-d de Gradle) — c'est lui qui trie à l'émission.
        // La CIBLE (protocole 5) part aussi : la clôture se calcule côté moteur.
        forgeBackend.build(new BuildRequest(buildId, projectPath, false, niveauConsole,
                        cible),
                this::onBuildEvent)
                .whenComplete((session, failure) -> {
                    if (failure != null) {
                        // Moteur non installé ou lancement refusé : Console honnête,
                        // pas d'échec muet (l'installation du moteur est L8).
                        onBackendLaunchFailure(failure);
                    } else if (session != null) {
                        // Forge v0.2.26+ (protocole 4) : le moteur compose et trie
                        // la console — l'atelier bascule en pur rendu (D-21).
                        consoleDuMoteur = session.engineConsole();
                    }
                });
    }

    /** Journal d'activité réel (filesDir/journal/activite.txt — L10). */
    private jo.joide.corestore.ActivityLog journalActivité() {
        return new jo.joide.corestore.ActivityLog(
                requireContext().getFilesDir().toPath().resolve("journal/activite.txt"));
    }

    /** Journal des écroulements réel (filesDir/journal/ecroulements.txt). */
    private jo.joide.corestore.CrashJournal crashJournal() {
        return new jo.joide.corestore.CrashJournal(
                requireContext().getFilesDir().toPath().resolve("journal/ecroulements.txt"));
    }

    /** Heure locale HH:mm pour l'en-tête de run de la console. */
    private String heure() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Lancement impossible (moteur absent, refus…) : console rouge honnête,
     *  barre en échec, feuille ouverte — jamais d'échec muet. */
    private void onBackendLaunchFailure(Throwable failure) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            buildRunning = false;
            // Lancement raté : le sémaphore retombe aussi (jamais suspendu).
            buildActivity.set(false);
            lastBuildEnd = System.currentTimeMillis();
            lastBuildSuccess = false;
            binding.dockProgression.setVisibility(View.GONE);
            rendreAvis();
            consoleBuffer.line(ConsoleBuffer.Kind.ERROR,
                    failure.getMessage() != null ? failure.getMessage()
                            : String.valueOf(failure));
            // Lancement impossible (moteur absent…) : journalé + feuille
            // d'erreur non fatale réutilisable (L10) — jamais muet.
            journalActivité().record(jo.joide.corestore.ActivityLog.Kind.CRASH,
                    getString(R.string.activite_lancement_impossible,
                            failure.getMessage() == null ? "" : failure.getMessage()),
                    null);
            ErrorSheet.montrer(getChildFragmentManager(),
                    getString(R.string.erreur_titre_lancement),
                    getString(R.string.erreur_message_lancement,
                            Paths.get(projectPath).getFileName().toString()),
                    failure);
            renderConsole();
            renderDockBuildPill();
            dockBehavior.setState(com.google.android.material.bottomsheet
                    .BottomSheetBehavior.STATE_HALF_EXPANDED);
        });
    }

    /** Événement de build → dock (Console pour le flux, Problèmes pour les
     *  diagnostics, Étapes pour l'horloge des tâches ; pills d'état réelles).
     *  Console du moteur (protocole 4) : les Log sont déjà composés et triés —
     *  l'atelier affiche (au seuil demandé, resserré) et ne compose plus rien. */
    private void onBuildEvent(BuildEvent event) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (event instanceof BuildEvent.Log log) {
                // Lignes du moteur (protocole 4 : déjà rédigées et triées) ou
                // journal brut (moteur ancien) — le seuil local ne peut que
                // resserrer (l'essentiel du tri s'est fait à l'émission).
                if (niveauAffiché(log.level())) {
                    consoleBuffer.line(kindFor(log.level()), log.message());
                }
            } else if (event instanceof BuildEvent.Download téléchargement) {
                // Moteur ancien : la ligne « ↓ fichier » est composée ici (INFO,
                // comme le moteur de référence). Moteur protocole 4 : déjà
                // composée par le moteur — l'événement structuré ne devient
                // JAMAIS une seconde ligne.
                if (!consoleDuMoteur && téléchargement.done()
                        && niveauAffiché(BuildEvent.Log.Level.INFO)) {
                    consoleBuffer.line(ConsoleBuffer.Kind.INFO, "↓ "
                            + téléchargement.resource() + " ("
                            + téléchargement.bytesDownloaded() / 1024 + " Kio)");
                }
            } else if (event instanceof BuildEvent.Heartbeat pulsation) {
                // Ligne vivante de la BARRE (tâche longue vivante) — pas de
                // ligne console : le moteur ne journalise pas ses pulsations,
                // le silence d'avant restait la norme sur le canal du journal.
                dernièrePulsation = pulsation;
                if (buildRunning) {
                    renderDockBuildPill();
                }
            } else if (event instanceof BuildEvent.DiagnosticEvent diagnostic) {
                addProblem(diagnostic.diagnostic());
            } else if (event instanceof BuildEvent.TaskState task) {
                onTaskState(task);
            } else if (event instanceof BuildEvent.Progress progress) {
                buildTasksTotal = progress.tasksTotal();
                buildTasksDone = progress.tasksDone();
                consoleBuffer.progress(getString(R.string.atelier_progression,
                        progress.tasksDone(), progress.tasksTotal()));
                renderDockBuildPill();
            } else if (event instanceof BuildEvent.Finished finished) {
                onBuildFinished(finished);
            }
            renderConsole();
        });
    }

    /** Nature de ligne console selon le niveau de journal du moteur. */
    private ConsoleBuffer.Kind kindFor(BuildEvent.Log.Level level) {
        return switch (level) {
            case DEBUG -> ConsoleBuffer.Kind.DEBUG;
            case INFO -> ConsoleBuffer.Kind.INFO;
            case LIFECYCLE -> ConsoleBuffer.Kind.LIFECYCLE;
            case WARNING -> ConsoleBuffer.Kind.WARNING;
            case ERROR -> ConsoleBuffer.Kind.ERROR;
        };
    }

    /** Tâche du graphe : une ligne console (remplacée en place) et une rangée
     *  d'étape (◐ devient ✔/✗/○) — durées réelles, jamais de « 2,4 » factice.
     *  Console du moteur (protocole 4) : les lignes de tâches sont déjà
     *  composées par le moteur — seule la rangée d'étape reste de l'atelier. */
    private void onTaskState(BuildEvent.TaskState task) {
        String name = task.task();
        String duree = task.durationMs() > 0
                ? " · " + Durations.format(task.durationMs(), Locale.getDefault())
                : "";
        switch (task.status()) {
            case STARTED -> {
                ligneTâche(name, ConsoleBuffer.Kind.TASK_RUNNING,
                        "◐ " + name + " " + getString(R.string.atelier_console_en_cours));
                upsertStepRow(name, "◐",
                        getString(R.string.atelier_etape_debut, name),
                        R.color.info, R.color.info);
            }
            case SUCCEEDED -> {
                ligneTâche(name, ConsoleBuffer.Kind.TASK_OK,
                        "✔ " + name + duree);
                upsertStepRow(name, "✔",
                        task.durationMs() > 0
                                ? getString(R.string.atelier_etape_tache, name,
                                        Durations.format(task.durationMs(),
                                                Locale.getDefault()))
                                : getString(R.string.atelier_etape_sans_duree, name),
                        R.color.ok, R.color.sur);
            }
            case FAILED -> {
                ligneTâche(name, ConsoleBuffer.Kind.TASK_FAILED,
                        "✗ " + name + " " + getString(R.string.atelier_console_echec) + duree);
                upsertStepRow(name, "✗",
                        getString(R.string.atelier_etape_echouee, name),
                        R.color.erreur, R.color.erreur);
            }
            case SKIPPED -> {
                ligneTâche(name, ConsoleBuffer.Kind.TASK_SKIPPED,
                        "○ " + name + " " + getString(R.string.atelier_console_eludee));
                upsertStepRow(name, "○",
                        getString(R.string.atelier_etape_eludee, name),
                        R.color.contour, R.color.sur_var);
            }
            case UP_TO_DATE -> {
                ligneTâche(name, ConsoleBuffer.Kind.TASK_UP_TO_DATE,
                        "✔ " + name + " " + getString(R.string.atelier_console_a_jour));
                upsertStepRow(name, "✔",
                        getString(R.string.atelier_etape_a_jour, name),
                        R.color.sur_var, R.color.sur_var);
            }
            default -> { }
        }
        // Le bilan « N tâches » compte les tâches terminées, pas les
        // événements (STARTED + SUCCEEDED = UNE tâche, pas deux).
        if (task.status() != BuildEvent.TaskState.Status.STARTED) {
            buildTaskCount++;
        }
    }

    /** Ligne de tâche en console — SAUF si le moteur compose déjà les siennes
     *  (protocole 4 : rien ne double) ; les rangées d'étapes restent de
     *  l'atelier dans tous les cas (événement structuré, pas console). */
    private void ligneTâche(String name, ConsoleBuffer.Kind kind, String text) {
        if (!consoleDuMoteur) {
            consoleBuffer.task(name, kind, text);
        }
    }

    // ---- Répertoire des tâches (protocoles 5-6, D-22/D-23 : le moteur énumère) -

    /** Répertoire des tâches du moteur pour le projet courant (null : inconnu). */
    private List<jo.joide.workbench.TaskInfo> répertoireTâches;

    /** Répertoire déjà demandé pour ce projet (une demande, jamais en plein build). */
    private boolean répertoireDemandé;

    /** Modules du projet, DÉDUITS du répertoire complet (préfixes des noms de
     *  tâches que le moteur a donnés) — la coquille regroupe ce qu'elle a reçu,
     *  elle ne devine pas les modules ; ils alimentent les chips du filtre
     *  (protocole 6, D-23 : l'équivalent de gradlew :core:tasks). */
    private List<String> modulesRépertoire = new ArrayList<>();

    /** Module filtré (null : tout le projet) — mémorisé entre les runs. */
    private String moduleRépertoireChoisi;

    /** Vues de sous-ensembles déjà rendues (une LECTURE moteur par module, pas
     *  une par touche — le moteur jetable coûte un lancement). */
    private final java.util.Map<String, List<jo.joide.workbench.TaskInfo>> sousEnsembles
            = new java.util.HashMap<>();

    /** Préférences du filtre de l'onglet Étapes (module mémorisé entre les runs). */
    private static final String PRÉFÉRENCES_ÉTAPES = "atelier_etapes";

    /** Clé du module mémorisé. */
    private static final String CLÉ_MODULE = "module";

    /** Étapes au repos en mode Forge : le RÉPERTOIRE du moteur (D-22) — la
     *  coquille affiche, elle ne devine plus ; une rangée touchée lance le build
     *  CIBLÉ (la clôture des dépendances se calcule côté moteur). L'horloge d'un
     *  build en cours prime toujours sur le répertoire ; l'historique d'un build
     *  terminé n'est jamais remplacé par la liste. Depuis le protocole 6 (D-23),
     *  le répertoire se demande PAR SOUS-ENSEMBLE : les chips Tout·app·core…
     *  re-posent la question au moteur (gradlew :core:tasks), la vue répond les
     *  agrégats PROPRES du module (core:assemble, core:clean). */
    private void chargerRépertoireSiVide() {
        if (buildRunning || répertoireDemandé || projectPath == null) {
            return;
        }
        if (binding.stepsList.getChildCount() > 0 || !stepRows.isEmpty()) {
            return;
        }
        assurerForgeBackend();
        répertoireDemandé = true;
        StepRow attente = stepRow("…",
                getString(R.string.atelier_etapes_repertoire),
                R.color.info, R.color.sur_var);
        binding.stepsList.addView(attente.row);
        forgeBackend.listTasks(projectPath).whenComplete((tâches, échec) -> {
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (buildRunning) {
                    return; // un build a démarré entre-temps : son horloge prime.
                }
                binding.stepsList.removeAllViews();
                stepRows.clear();
                if (échec != null) {
                    StepRow refus = stepRow("○",
                            getString(R.string.atelier_etapes_indisponible,
                                    messageDe(échec)),
                            R.color.contour, R.color.sur_var);
                    binding.stepsList.addView(refus.row);
                    return;
                }
                répertoireTâches = tâches;
                modulesRépertoire = modulesDe(tâches);
                // Choix mémorisé (protocole 6, D-23) : chargé une fois, puis
                // honnête — un module absent du répertoire courant retombe sur
                // « Tout » (le projet a changé de forme entre les runs).
                if (moduleRépertoireChoisi == null) {
                    String mémorisé = requireContext()
                            .getSharedPreferences(PRÉFÉRENCES_ÉTAPES, Context.MODE_PRIVATE)
                            .getString(CLÉ_MODULE, "");
                    moduleRépertoireChoisi = mémorisé == null || mémorisé.isEmpty()
                            ? null : mémorisé;
                }
                moduleRépertoireChoisi = modulesRépertoire.contains(moduleRépertoireChoisi)
                        ? moduleRépertoireChoisi : null;
                construireChipsModules();
                if (moduleRépertoireChoisi == null) {
                    rendreRépertoire(répertoireTâches);
                } else {
                    chargerSousEnsemble(moduleRépertoireChoisi);
                }
            });
        });
    }

    /** Les modules du répertoire : préfixes des noms qualifiés (le moteur a donné
     *  les noms, la coquille regroupe — ex. app:compileJava → app), triés. */
    private static List<String> modulesDe(List<jo.joide.workbench.TaskInfo> tâches) {
        java.util.Set<String> modules = new java.util.TreeSet<>();
        for (jo.joide.workbench.TaskInfo tâche : tâches) {
            int deuxPoints = tâche.name().indexOf(':');
            if (deuxPoints > 0) {
                modules.add(tâche.name().substring(0, deuxPoints));
            }
        }
        return new ArrayList<>(modules);
    }

    /** Construit la rangée de chips du filtre (Tout + un chip par module), en
     *  tête de la liste des étapes — elle vit avec le répertoire : partie au
     *  départ d'un build (la liste est vidée), absente de l'horloge. */
    private void construireChipsModules() {
        if (modulesRépertoire.isEmpty()) {
            return; // projet mono-module : rien à filtrer, aucune chip.
        }
        HorizontalScrollView défilement = new HorizontalScrollView(requireContext());
        défilement.setHorizontalScrollBarEnabled(false);
        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.HORIZONTAL);
        rangée.setPadding(0, 0, dip(10), dip(6));
        défilement.addView(rangée);
        TextView tout = chipModule(null, getString(R.string.atelier_etapes_filtre_tout));
        rangée.addView(tout);
        for (String module : modulesRépertoire) {
            rangée.addView(chipModule(module, module));
        }
        binding.stepsList.addView(défilement);
        styliserChipsModules();
    }

    /** Une chip de filtre (studio : 28 dp, ON = sc2) ; « Tout » porte le null. */
    private TextView chipModule(String module, String libellé) {
        TextView chip = new TextView(requireContext());
        chip.setText(libellé);
        chip.setTag(module);
        chip.setTextSize(12.5f);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(dip(14), 0, dip(14), 0);
        chip.setContentDescription(getString(R.string.atelier_etapes_filtre_module_cd)
                + " : " + libellé);
        chip.setOnClickListener(v -> choisirModuleRépertoire(module));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(28));
        params.rightMargin = dip(8);
        chip.setLayoutParams(params);
        return chip;
    }

    /** Choix du filtre : mémorisé, stylisé, puis la vue suit (cache ou question). */
    private void choisirModuleRépertoire(String module) {
        if (buildRunning) {
            return; // l'horloge prime : le filtre reprendra au repos.
        }
        moduleRépertoireChoisi = module;
        requireContext().getSharedPreferences(PRÉFÉRENCES_ÉTAPES, Context.MODE_PRIVATE)
                .edit().putString(CLÉ_MODULE, module == null ? "" : module).apply();
        styliserChipsModules();
        binding.stepsList.removeAllViews();
        stepRows.clear();
        construireChipsModules();
        if (module == null) {
            rendreRépertoire(répertoireTâches);
        } else {
            chargerSousEnsemble(module);
        }
    }

    /** Charge la vue d'un module : le cache d'abord (une LECTURE par module),
     *  sinon la question au moteur — la rangée d'attente puis la vue, ou le refus
     *  honnête en une rangée (module inconnu, moteur ancien). */
    private void chargerSousEnsemble(String module) {
        List<jo.joide.workbench.TaskInfo> enCache = sousEnsembles.get(module);
        if (enCache != null) {
            rendreRépertoire(enCache);
            return;
        }
        StepRow attente = stepRow("…",
                getString(R.string.atelier_etapes_repertoire_module, module),
                R.color.info, R.color.sur_var);
        binding.stepsList.addView(attente.row);
        forgeBackend.listTasks(projectPath, List.of(module)).whenComplete((tâches, échec) -> {
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (buildRunning) {
                    return; // un build a démarré entre-temps : son horloge prime.
                }
                if (échec != null) {
                    binding.stepsList.removeAllViews();
                    stepRows.clear();
                    construireChipsModules();
                    StepRow refus = stepRow("○",
                            getString(R.string.atelier_etapes_indisponible,
                                    messageDe(échec)),
                            R.color.contour, R.color.sur_var);
                    binding.stepsList.addView(refus.row);
                    return;
                }
                sousEnsembles.put(module, tâches);
                binding.stepsList.removeAllViews();
                stepRows.clear();
                construireChipsModules();
                rendreRépertoire(tâches);
            });
        });
    }

    /** Style des chips du filtre : la sélection prend le fond sc2 du studio. */
    private void styliserChipsModules() {
        LinearLayout rangée = rangéeChipsModules();
        if (rangée == null) {
            return;
        }
        for (int i = 0; i < rangée.getChildCount(); i++) {
            TextView chip = (TextView) rangée.getChildAt(i);
            boolean on = java.util.Objects.equals((String) chip.getTag(),
                    moduleRépertoireChoisi);
            chip.setBackground(getResources().getDrawable(
                    on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    /** La rangée des chips dans la liste des étapes (null : absente). */
    private LinearLayout rangéeChipsModules() {
        if (binding.stepsList.getChildCount() == 0
                || !(binding.stepsList.getChildAt(0) instanceof HorizontalScrollView défilement)
                || !(défilement.getChildAt(0) instanceof LinearLayout rangée)) {
            return null;
        }
        return rangée;
    }

    /** Rend les rangées du répertoire (complet ou sous-ensemble) : ○ concret,
     *  ◐ cycle de vie (les agrégats du moteur : assemble, build, core:assemble…),
     *  une rangée touchée lance le build CIBLÉ — la cible part telle quelle, la
     *  clôture se calcule côté moteur. */
    private void rendreRépertoire(List<jo.joide.workbench.TaskInfo> tâches) {
        for (jo.joide.workbench.TaskInfo tâche : tâches) {
            boolean cycle = tâche.lifecycle();
            StepRow rangée = stepRow(cycle ? "◐" : "○",
                    tâche.name() + (tâche.description().isEmpty()
                            ? "" : " — " + tâche.description()),
                    cycle ? R.color.info : R.color.sur_var,
                    cycle ? R.color.info : R.color.sur);
            rangée.row.setOnClickListener(v -> {
                if (!buildRunning) {
                    toast(getString(R.string.atelier_etape_lancee,
                            tâche.name()));
                    runBuild(List.of(tâche.name()));
                }
            });
            binding.stepsList.addView(rangée.row);
        }
    }

    /** Message le plus profond d'un échec asynchrone (la cause utile, pas l'enveloppe). */
    private static String messageDe(Throwable échec) {
        Throwable cause = échec;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause);
    }

    /** Rangée d'étape vivante : créée au démarrage, remplacée au terminal. */
    private void upsertStepRow(String task, String marker, String text,
            int markerColorRes, int textColorRes) {
        StepRow existing = stepRows.get(task);
        if (existing == null) {
            StepRow row = stepRow(marker, text, markerColorRes, textColorRes);
            stepRows.put(task, row);
            binding.stepsList.addView(row.row);
        } else {
            existing.marker.setText(marker);
            existing.marker.setTextColor(getResources().getColor(markerColorRes));
            existing.label.setText(text);
            existing.label.setTextColor(getResources().getColor(textColorRes));
        }
        followBottom(binding.stepsSection);
    }

    /** Suit le bas d'une liste défilante sauf si l'utilisateur a remonté. */
    private void followBottom(ScrollView scroll) {
        View child = scroll.getChildAt(0);
        if (child == null || scroll.getScrollY()
                >= child.getHeight() - scroll.getHeight() - dip(24)) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** Fin de build : bilan (durée + tâches réelles), pills, barre du dock,
     *  résumé des problèmes ; échec → la feuille s'ouvre sur Problèmes,
     *  réussite → la barre seule, jamais de vol de focus (preview).
     *  Console du moteur (protocole 4) : le verdict est DÉJÀ dans le journal
     *  (ligne LIFECYCLE composée par le moteur) — pas de seconde ligne ici. */
    private void onBuildFinished(BuildEvent.Finished finished) {
        buildRunning = false;
        // Le build est fini : le sémaphore retombe, la re-passe d'index
        // (classpaths frais) peut reprendre (L10).
        buildActivity.set(false);
        lastBuildEnd = System.currentTimeMillis();
        lastBuildSuccess = finished.success();
        binding.dockProgression.setVisibility(View.GONE);
        rendreAvis();
        String duree = Durations.format(finished.durationMs(), Locale.getDefault());
        int taches = Math.max(buildTasksTotal, buildTaskCount);
        // Mort anormale du moteur (la session se clôt sans fin de build) :
        // écroulement journalé + ligne honnête — l'IDE est intact (L10).
        boolean moteurÉcroulé = !finished.success() && finished.message() != null
                && finished.message().contains("mort anormale");
        if (!consoleDuMoteur) {
            if (finished.success()) {
                consoleBuffer.finished(ConsoleBuffer.Kind.SUCCESS,
                        "✔ " + getString(R.string.atelier_console_reussi, duree, taches));
            } else {
                String fautive = finished.failedTask() != null
                        ? " · " + getString(R.string.atelier_console_tache_fautive,
                                finished.failedTask())
                        : "";
                consoleBuffer.finished(ConsoleBuffer.Kind.FAILURE,
                        "✗ " + getString(R.string.atelier_console_echoue, duree) + fautive);
                if (finished.message() != null && !finished.message().isBlank()) {
                    consoleBuffer.line(ConsoleBuffer.Kind.ERROR, finished.message());
                }
            }
        }
        if (moteurÉcroulé) {
            // Ligne honnête de la référence : l'IDE survit au moteur.
            consoleBuffer.line(ConsoleBuffer.Kind.ERROR,
                    getString(R.string.atelier_moteur_ecroule));
            journalActivité().record(jo.joide.corestore.ActivityLog.Kind.CRASH,
                    getString(R.string.atelier_moteur_ecroule_journal),
                    finished.message());
            crashJournal().record(jo.joide.corestore.CrashJournal.Zone.MOTEUR,
                    new RuntimeException(finished.message() == null
                            ? "moteur mort" : finished.message()));
        } else {
            journalActivité().record(jo.joide.corestore.ActivityLog.Kind.VERDICT,
                    finished.success()
                            ? getString(R.string.activite_build_reussi, duree, taches)
                            : getString(R.string.activite_build_echoue, duree,
                                    finished.failedTask() == null ? "—"
                                            : finished.failedTask()),
                    finished.message());
        }
        renderBuildPills();
        renderDockBuildPill();
        renderProblemsSummary();
        // Lot 2 tiroir : les décorations de l'arbre relisent les
        // diagnostics (un seul événement par build, pas par diagnostic).
        workspaceEvents.publish(
                new jo.joide.workbench.WorkspaceEvents.DiagnosticsChanged());
        if (!finished.success()) {
            showDockSection(1);
            dockBehavior.setState(com.google.android.material.bottomsheet
                    .BottomSheetBehavior.STATE_HALF_EXPANDED);
        }
    }

    /** Pills d'état réelles (ok/err/warn — compteurs par sévérité, plus de
     *  compte d'enfants qui confondait avertissements et erreurs). */
    private void renderBuildPills() {
        binding.buildStateOk.setVisibility(
                lastBuildSuccess ? View.VISIBLE : View.GONE);
        if (lastBuildSuccess) {
            binding.buildStateOk.setText(getString(R.string.atelier_build_ok_pill));
        }
        binding.buildStateErr.setVisibility(
                buildErrorCount > 0 ? View.VISIBLE : View.GONE);
        if (buildErrorCount > 0) {
            binding.buildStateErr.setText(getString(
                    R.string.atelier_nb_erreurs, buildErrorCount));
        }
        binding.buildStateWarn.setVisibility(
                buildWarningCount > 0 ? View.VISIBLE : View.GONE);
        if (buildWarningCount > 0) {
            binding.buildStateWarn.setText(getString(
                    R.string.atelier_nb_avertissements, buildWarningCount));
        }
    }

    /** Barre du dock : Prêt / ◐ en cours (compteur réel) / ▶ ok + âge /
     *  ✗ échec — la couleur suit l'état, jamais figé (preview). */
    private void renderDockBuildPill() {
        TextView pill = binding.dockBuildState;
        if (buildRunning) {
            if (dernièrePulsation != null) {
                // Ligne vivante (protocole 3+) : la tâche longue qui travaille
                // MAINTENANT, avec son âge — le silence n'est plus un plantage.
                pill.setText("◐ " + dernièrePulsation.task() + " · "
                        + Durations.format(dernièrePulsation.elapsedMs(),
                                Locale.getDefault()));
            } else {
                pill.setText(buildTasksTotal > 0
                        ? getString(R.string.dock_etat_cours_nb, buildTasksDone,
                                buildTasksTotal)
                        : getString(R.string.dock_etat_cours));
            }
            pill.setBackgroundResource(R.drawable.fond_pill_info);
            pill.setTextColor(getResources().getColor(R.color.info));
        } else if (lastBuildEnd > 0 && lastBuildSuccess) {
            pill.setText(getString(R.string.dock_etat_ok, relativeBuildAge()));
            pill.setBackgroundResource(R.drawable.fond_pill_ok);
            pill.setTextColor(getResources().getColor(R.color.ok));
        } else if (lastBuildEnd > 0) {
            pill.setText(getString(R.string.dock_etat_err));
            pill.setBackgroundResource(R.drawable.fond_pill_err);
            pill.setTextColor(getResources().getColor(R.color.erreur));
        } else {
            pill.setText(getString(R.string.dock_etat_pret));
            pill.setBackgroundResource(R.drawable.fond_pill_sc_highest);
            pill.setTextColor(getResources().getColor(R.color.sur_var));
        }
        // Ligne fine du bord : déterminée quand le total est connu (référence).
        if (binding.dockProgression.getVisibility() == View.VISIBLE) {
            binding.dockProgression.setIndeterminate(buildTasksTotal <= 0);
            if (buildTasksTotal > 0) {
                binding.dockProgression.setProgress(
                        Math.round(buildTasksDone * 100f / buildTasksTotal));
            }
        }
    }

    /** Âge relatif du dernier build (« à l'instant », « il y a 3 min »…). */
    private String relativeBuildAge() {
        jo.joide.ai.RelativeTimes.Ago ago = jo.joide.ai.RelativeTimes.agoOf(
                lastBuildEnd, System.currentTimeMillis());
        return switch (ago.unit()) {
            case MINUTES -> ago.value() <= 0
                    ? getString(R.string.time_now)
                    : getString(R.string.time_min_ago, ago.value());
            case HOURS -> getString(R.string.time_hours_ago, ago.value());
            case DAYS -> getString(R.string.time_days_ago, ago.value());
        };
    }

    /** Diagnostic réel : rangée cliquable + compteurs par sévérité. */
    private void addProblem(jo.joide.workbench.Diagnostic diagnostic) {
        binding.problemsList.addView(problemRow(diagnostic));
        if (diagnostic.severity() == jo.joide.workbench.Diagnostic.Severity.ERROR) {
            buildErrorCount++;
        } else if (diagnostic.severity()
                == jo.joide.workbench.Diagnostic.Severity.WARNING) {
            buildWarningCount++;
        }
        // Diagnostic réel gardé pour le contexte de l'agent (Lot 0).
        lastDiagnostics.add(diagnostic.file()
                + (diagnostic.line() > 0 ? ":" + diagnostic.line() : "")
                + ": " + diagnostic.message());
        // Lot 2 tiroir : la forme structurée nourrit les décorations.
        lastStructuredDiagnostics.add(diagnostic);
    }

    /** Pied de la section Problèmes : « 2 erreurs · 2 avertissements »,
     *  ou « Aucun problème — build propre » (preview). */
    private void renderProblemsSummary() {
        String resume = buildErrorCount == 0 && buildWarningCount == 0
                ? getString(R.string.atelier_problemes_propre)
                : getString(R.string.atelier_problemes_resume,
                        buildErrorCount, buildWarningCount);
        if (problemsSummary == null) {
            problemsSummary = new TextView(requireContext());
            problemsSummary.setTextSize(11f);
            problemsSummary.setPadding(dip(2), dip(8), dip(2), dip(2));
            problemsSummary.setTextColor(getResources().getColor(R.color.contour));
            binding.problemsList.addView(problemsSummary);
        }
        problemsSummary.setText(resume);
    }

    /** Une rangée de problème (preview : ✗/⚠/▸ + position) — appui : ouvre
     *  le fichier à la ligne ; appui long : « Expliquer avec l'agent IA »
     *  (l'ancien code posait DEUX écouteurs, le second écrasait le dialogue
     *  agent — il n'était jamais atteignable). */
    private View problemRow(jo.joide.workbench.Diagnostic diagnostic) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        String position = diagnostic.file()
                + (diagnostic.line() > 0 ? ":" + diagnostic.line() : "");
        // Saut au fichier depuis la section Problèmes (fiche atelier J2).
        row.setOnClickListener(v -> {
            Path target = Paths.get(projectPath).resolve(diagnostic.file());
            if (Files.isRegularFile(target)) {
                openFile(target);
            }
        });
        // Lot 8 : « Expliquer cette erreur » — l'agent reçoit le diagnostic
        // exact (fichier, ligne, message).
        row.setOnLongClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    requireContext())
                    .setTitle(R.string.problems_explain_title)
                    .setMessage(position + "\n" + diagnostic.message())
                    .setPositiveButton(R.string.problems_explain_action,
                            (d, w) -> openAgentPanel(getString(
                                    R.string.problems_explain_prompt,
                                    diagnostic.file(),
                                    diagnostic.line() > 0 ? ":" + diagnostic.line() : "",
                                    diagnostic.message())))
                    .setNegativeButton(R.string.commun_annuler, null)
                    .show();
            return true;
        });
        boolean error = diagnostic.severity()
                == jo.joide.workbench.Diagnostic.Severity.ERROR;
        boolean warning = diagnostic.severity()
                == jo.joide.workbench.Diagnostic.Severity.WARNING;
        TextView marker = new TextView(requireContext());
        marker.setText(error ? "✗" : warning ? "⚠" : "▸");
        marker.setTextColor(getResources().getColor(
                error ? R.color.erreur : warning ? R.color.alerte : R.color.info));
        marker.setTextSize(11.5f);
        row.addView(marker, new LinearLayout.LayoutParams(
                dip(14), ViewGroup.LayoutParams.WRAP_CONTENT));

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
        message.setTypeface(Typeface.MONOSPACE);
        text.addView(message);
        TextView positionView = new TextView(requireContext());
        positionView.setText(position);
        positionView.setTextColor(getResources().getColor(R.color.sur_var));
        positionView.setTextSize(11.5f);
        positionView.setTypeface(Typeface.MONOSPACE);
        text.addView(positionView);
        row.addView(text);
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

    /** Rendu chrome selon le mode : chip, avis unifiés, terminal (studio, modeEditor). */
    private void renderModeChrome(WorkMode mode) {
        binding.modeChip.setText(mode == WorkMode.GRADLE
                ? getString(R.string.mode_gradle_nom) : getString(R.string.mode_forge_nom));
        rendreAvis();
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

    // ---- Bandeau unifié des avis (référence EditorNoticeStrip) ---------------

    /** Un avis vivant : texte, action unique, masquable. */
    private static final class Avis {
        final String texte;
        final String action;
        final Runnable auClic;
        final Runnable auMasquage;

        Avis(String texte, String action, Runnable auClic, Runnable auMasquage) {
            this.texte = texte;
            this.action = action;
            this.auClic = auClic;
            this.auMasquage = auMasquage;
        }
    }

    /**
     * UN bandeau pour tous les avis du projet (référence EditorNoticeStrip) :
     * un avis = sa rangée ; plusieurs = « N avis demandent votre attention »
     * dépliable. Teinte alerte à 10 %, action à droite, croix pour masquer.
     * Les avis sont RÉELS : notice du mode, index interrompu, build échoué.
     */
    private void rendreAvis() {
        if (binding == null) {
            return;
        }
        List<Avis> vivants = new ArrayList<>();
        if (!noticeDismissed) {
            vivants.add(new Avis(
                    activeMode() == WorkMode.GRADLE
                            ? getString(R.string.atelier_notice_gradle)
                            : getString(R.string.atelier_notice_forge),
                    null, null, () -> {
                        noticeDismissed = true;
                        rendreAvis();
                    }));
        }
        if (indexInterrompu && !avisIndexMasqué) {
            vivants.add(new Avis(getString(R.string.atelier_avis_index),
                    null, null, () -> {
                        avisIndexMasqué = true;
                        rendreAvis();
                    }));
        }
        if (lastBuildEnd > 0 && !lastBuildSuccess && !avisBuildMasqué) {
            vivants.add(new Avis(
                    getString(R.string.atelier_avis_build_echoue,
                            Math.max(1, buildErrorCount)),
                    getString(R.string.atelier_avis_action_console),
                    () -> dockBehavior.setState(com.google.android.material.bottomsheet
                            .BottomSheetBehavior.STATE_HALF_EXPANDED),
                    () -> {
                        avisBuildMasqué = true;
                        rendreAvis();
                    }));
        }
        if (vivants.isEmpty()) {
            binding.noticeStrip.setVisibility(View.GONE);
            return;
        }
        int teinte = getResources().getColor(R.color.alerte);
        binding.noticeStrip.setVisibility(View.VISIBLE);
        binding.noticeStrip.setBackgroundColor(
                (teinte & 0x00FFFFFF) | 0x1A000000); // 10 % comme la référence
        boolean plusieurs = vivants.size() > 1;
        binding.noticeResume.setVisibility(plusieurs ? View.VISIBLE : View.GONE);
        if (plusieurs) {
            binding.noticeCompte.setText(
                    getString(R.string.atelier_avis_plusieurs, vivants.size()));
            binding.noticeCaret.setImageResource(avisDépliés
                    ? R.drawable.ic_chevd : R.drawable.ic_chevr);
            binding.noticeResume.setOnClickListener(v -> {
                avisDépliés = !avisDépliés;
                rendreAvis();
            });
        }
        binding.noticeRangees.removeAllViews();
        if (plusieurs && !avisDépliés) {
            return; // replié : le résumé seul se voit (référence)
        }
        for (Avis avis : vivants) {
            binding.noticeRangees.addView(rangéeAvis(avis));
        }
    }

    /** Une rangée d'avis : texte (2 lignes max) · action · masquer. */
    private View rangéeAvis(Avis avis) {
        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.HORIZONTAL);
        rangée.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rangée.setPadding(dip(14), dip(8), dip(6), dip(8));
        TextView texte = new TextView(requireContext());
        texte.setText(avis.texte);
        texte.setTextSize(12.5f);
        texte.setTextColor(getResources().getColor(R.color.sur_var));
        texte.setMaxLines(2);
        texte.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rangée.addView(texte, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (avis.action != null && avis.auClic != null) {
            TextView action = new TextView(requireContext());
            action.setText(avis.action);
            action.setTextSize(12.5f);
            action.setTypeface(action.getTypeface(), android.graphics.Typeface.BOLD);
            action.setTextColor(getResources().getColor(R.color.alerte));
            action.setPadding(dip(8), dip(2), dip(8), dip(2));
            action.setOnClickListener(v -> avis.auClic.run());
            rangée.addView(action);
        }
        if (avis.auMasquage != null) {
            ImageButton masquer = new ImageButton(requireContext());
            masquer.setBackground(null);
            masquer.setImageResource(R.drawable.ic_x);
            masquer.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            masquer.setColorFilter(getResources().getColor(R.color.sur_var));
            masquer.setContentDescription(getString(R.string.option_atelier_masquer_notice));
            masquer.setPadding(dip(6), dip(6), dip(6), dip(6));
            masquer.setOnClickListener(v -> avis.auMasquage.run());
            rangée.addView(masquer, new LinearLayout.LayoutParams(dip(32), dip(32)));
        }
        return rangée;
    }

    // ---- Journal --------------------------------------------------------------

    /** Ajoute une ligne au journal IDE (section Journal, jamais muet). */
    private void appendToJournal(String line) {
        binding.ideJournal.append(line + "\n");
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
