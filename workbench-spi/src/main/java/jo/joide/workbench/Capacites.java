package jo.joide.workbench;

import java.util.List;

/**
 * Capacités publiées honnêtement par un backend : l'interface n'offre jamais un bouton
 * que le mode ne peut pas tenir (conception §4). Un besoin manquant chez Forge devient
 * une demande formelle (docs/DEMANDES-FORGE.md), jamais un contournement silencieux.
 */
public record Capacites(
        boolean projetsForge,
        boolean scriptsGradle,
        boolean aar,
        boolean viewBinding,
        boolean terminal,
        List<String> limitesAffichees) {
    public Capacites {
        limitesAffichees = limitesAffichees == null ? List.of() : List.copyOf(limitesAffichees);
    }
}
