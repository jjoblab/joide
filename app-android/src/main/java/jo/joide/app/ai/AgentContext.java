package jo.joide.app.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

/**
 * Contexte vivant de l'atelier pour l'agent du projet (Lot 0 de la séparation
 * Assistant / Agent) : les puces de contexte du tiroir agent affichent et
 * joignent au prochain message des valeurs **réelles** fournies par l'hôte
 * ({@link jo.joide.app.WorkbenchFragment}) — fichier ouvert (tampon vivant
 * de l'éditeur, pas le disque), diagnostics du dernier build, sélection
 * courante. Aucune valeur n'est inventée : une valeur absente est
 * {@code null} / liste vide et la puce correspondante reste masquée.
 *
 * <p>Les méthodes se lisent sur le fil UI (elles touchent l'éditeur et les
 * vues) ; l'appelant capture les chaînes AVANT de partir en tâche de fond.</p>
 */
public interface AgentContext {

    /** Nom du fichier ouvert dans l'éditeur ; null si aucun. */
    @Nullable
    String agentActiveFileName();

    /** Chemin du fichier ouvert relatif à la racine du projet ; null si aucun. */
    @Nullable
    String agentActiveFilePath();

    /** Contenu du tampon vivant de l'éditeur (modifications non enregistrées comprises) ; null si aucun. */
    @Nullable
    String agentActiveFileContent();

    /** Diagnostics du dernier build, « fichier:ligne: message » ; vide si aucun build ou aucun problème. */
    @NonNull
    List<String> agentProblemLines();

    /** Sélection courante de l'éditeur ; null si vide (curseur seul). */
    @Nullable
    String agentSelectionText();

    /** Branche Git courante du projet ; null si pas de dépôt. */
    @Nullable
    String agentBranchName();
}
