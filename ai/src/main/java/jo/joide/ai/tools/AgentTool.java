package jo.joide.ai.tools;

import java.util.List;

/**
 * Outil de l'agent (Lot 3) : un fichier par outil dans {@code ai/tools/},
 * schéma JSON complet, résultats bornés, erreurs d'argument explicites
 * renvoyées au modèle. Les outils ne sortent plus d'{@code AgentLoop}.
 *
 * <p>{@link ToolHost} est le port des services vivants de l'hôte (diagnostics
 * réels, tampon de l'éditeur) : une valeur absente est {@code null} et l'outil
 * le dit honnêtement au modèle — jamais de valeur inventée.</p>
 */
public interface AgentTool {

    /** Nom exposé au modèle (unique). */
    String name();

    /** Description (moitié stable du prompt). */
    String description();

    /** Schéma JSON des paramètres (sérialisé avec l'outil). */
    String schema();

    /** Lecture seule ? (les lectures sont parallèles, les mutations séquentielles et gated). */
    boolean readOnly();

    /** Exécute l'outil sur l'espace de travail ; le retour (borné) part au modèle. */
    String run(jo.joide.ai.AgentWorkspace workspace, ToolArgs args) throws Exception;

    /** Services vivants de l'hôte (diagnostics, éditeur) ; valeurs réelles ou null. */
    interface ToolHost {

        /** Diagnostics du dernier build (« fichier:ligne: message ») ; null si indisponible. */
        List<String> problemLines();

        /** Contenu du tampon vivant du fichier ouvert ; null si aucun. */
        String openFileContent();

        /** Nom du fichier ouvert ; null si aucun. */
        String openFileName();

        /** Sélection courante de l'éditeur ; null si vide. */
        String selectionText();
    }
}
