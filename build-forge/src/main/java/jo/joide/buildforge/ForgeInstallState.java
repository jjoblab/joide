package jo.joide.buildforge;

import jo.joide.workbench.InstallState;

/**
 * État d'installation du moteur Forge : délègue au module {@code jo.forge.setup}
 * (installateur : manifeste signé, reprise, installation atomique). En bac à sable
 * (sans appareil), l'état reste NOT_INSTALLED.
 */
final class ForgeInstallState {

    InstallState current() {
        return InstallState.NOT_INSTALLED;
    }
}
