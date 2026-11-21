package jo.joide.app.drawer;

import jo.joide.coregit.GitService;
import jo.joide.files.FileKind;
import jo.joide.workbench.Diagnostic;

/**
 * Une rangée d'entrée de l'arborescence (Lots 2-3) : dossier ou fichier,
 * avec guides d'indentation ({@code depth}), icône par type ({@code kind}),
 * nom affiché ({@code display} — une chaîne compacte « com.example.app »
 * pour les packages), décorations git et diagnostics, fichier actif,
 * sélection multiple et badge d'éléments des dossiers repliés.
 *
 * <p>Enregistrement immuable : le rendu reconstruit les rangées et
 * {@code DiffUtil} anime les différences.</p>
 *
 * @param relativePath  chemin relatif **réel** (clé d'expansion : la fin de
 *                      chaîne compacte pour un package compacté).
 * @param opPath        chemin relatif du **premier** dossier (clé des
 *                      opérations : sélection, renommage, corbeille).
 * @param display       nom affiché (chaîne compacte le cas échéant).
 * @param directory     l'entrée est-elle un dossier ?
 * @param depth         profondeur (0 = racine) pour les guides.
 * @param kind          type fin (icône), jamais d'extension côté UI.
 * @param open          dossier ouvert (chevron vers le bas).
 * @param git           décoration git (null = aucune).
 * @param problem       sévérité max (null = aucune) — héritée par les parents.
 * @param active        fichier actif de l'éditeur.
 * @param selected      sélectionné (mode sélection multiple).
 * @param selectionMode mode sélection actif (cases visibles).
 * @param badge         compte d'éléments d'un dossier replié (null = aucun).
 */
record TreeRow(String relativePath, String opPath, String display,
        boolean directory, int depth, FileKind kind, boolean open,
        GitService.ChangeKind git, Diagnostic.Severity problem, boolean active,
        boolean selected, boolean selectionMode, String badge) {
}
