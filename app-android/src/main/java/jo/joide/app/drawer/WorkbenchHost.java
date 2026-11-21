package jo.joide.app.drawer;

import androidx.annotation.Nullable;
import java.nio.file.Path;

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

    /** Fichier actif de l'éditeur ; null si aucun. */
    @Nullable
    Path activeFile();

    /** Ferme le tiroir gauche (contenu poussé : rien n'est perdu). */
    void closeLeftDrawer();

    /** Ouvre l'écran Git plein écran (comportement conservé du Lot 0). */
    void openGitScreen();
}
