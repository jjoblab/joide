package jo.joide.buildforge;

import jo.joide.workbench.EtatInstallation;

/**
 * État d'installation du moteur Forge : délègue au module {@code jo.forge.setup}
 * (installateur : manifeste signé, reprise, installation atomique). En bac à sable
 * (sans appareil), l'état reste NON_INSTALLE.
 */
final class GestionnaireInstallationForge {

    EtatInstallation etat() { return EtatInstallation.NON_INSTALLE; }
}
