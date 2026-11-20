package jo.joide.workbench;

import java.util.List;

/**
 * Capacités publiées honnêtement par un backend : l'interface n'offre jamais un bouton
 * que le mode ne peut pas tenir (conception §4). Un besoin manquant chez Forge devient
 * une demande formelle (docs/DEMANDES-FORGE.md), jamais un contournement silencieux.
 *
 * @param forgeProjects projets {@code forge.json} constructibles.
 * @param gradleScripts scripts Gradle constructibles.
 * @param aar           bibliothèques AAR supportées.
 * @param viewBinding   ViewBinding supporté.
 * @param terminal      terminal intégré disponible.
 * @param limitations   limites à afficher telles quelles à l'utilisateur.
 */
public record Capabilities(boolean forgeProjects, boolean gradleScripts, boolean aar,
                           boolean viewBinding, boolean terminal,
                           List<String> limitations) {

    public Capabilities {
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
