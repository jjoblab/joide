package jo.joide.app.drawer;

import androidx.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import jo.joide.workbench.Diagnostic;

/**
 * Contrat tiroir gauche ↔ atelier (Lot 0) : le tiroir ne manipule **jamais**
 * directement l'éditeur — tout passe par l'hôte ({@code WorkbenchFragment}).
 *
 * <p>Version minimale du contrat (Lot 0 = extraction sans régression) :
 * ouvrir un fichier, fichier actif, fermer le tiroir, écran Git. Les
 * méthodes complètes (onglets ouverts, sélection, diffs, diagnostics,
 * rechargement disque) arrivent avec les lots qui les implémentent.</p>
 */
public interface WorkbenchHost {

    /** Ouvre un fichier dans l'éditeur (onglet actif). */
    void openFile(Path file);

    /**
     * Ouvre un fichier et **sélectionne une occurrence** (Lot 4 : résultat
     * de recherche). Ligne et colonne 1-based, longueur de la sélection.
     */
    void openFileAt(Path file, int line, int column, int length);

    /**
     * Fichiers récemment ouverts (du plus récent au plus ancien) — Lot 4 :
     * classement de « aller au fichier ».
     */
    List<Path> recentFiles();

    /** Fichier actif de l'éditeur ; null si aucun. */
    @Nullable
    Path activeFile();

    /**
     * Diagnostics structurés du dernier build (Lot 2 : décorations de
     * l'arborescence — pastille erreur/avertissement, héritée par les
     * dossiers parents).
     */
    List<Diagnostic> diagnostics();

    /** Ferme le tiroir gauche (contenu poussé : rien n'est perdu). */
    void closeLeftDrawer();

    /** Ouvre l'écran Git plein écran (comportement conservé du Lot 0). */
    void openGitScreen();
}
