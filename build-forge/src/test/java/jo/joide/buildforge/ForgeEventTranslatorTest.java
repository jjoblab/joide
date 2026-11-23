package jo.joide.buildforge;

import jo.forge.api.BuildEvent;
import jo.joide.workbench.BuildRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Le traducteur d'événements, niveau par niveau (Forge 0.2.26, protocole 4) :
 * LIFECYCLE arrive EN TANT QUE TEL (le moteur compose la console — décision
 * D-21), les téléchargements et pulsations deviennent des événements propres
 * de l'atelier (plus JAMAIS le repli toString qui polluait la console).
 */
class ForgeEventTranslatorTest {

    @Test
    void lifecycleLinesKeepTheirLevel() {
        BuildEvent.LogEvent ligne =
                new BuildEvent.LogEvent("b", 1L, BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                        "> Tâche app:compileJava — À JOUR");
        jo.joide.workbench.BuildEvent.Log traduit =
                (jo.joide.workbench.BuildEvent.Log) ForgeEventTranslator.translate(ligne);
        assertEquals(jo.joide.workbench.BuildEvent.Log.Level.LIFECYCLE,
                traduit.level(), "LIFECYCLE se traduit tel quel — le niveau de Gradle");
        assertEquals("> Tâche app:compileJava — À JOUR", traduit.message());
    }

    @Test
    void severityOrderMatchesGradle() {
        // L'ordinal du contrat suit la sévérité : DEBUG < INFO < LIFECYCLE <
        // WARNING < ERROR (c'est LUI que borne le seuil d'affichage de l'atelier).
        jo.joide.workbench.BuildEvent.Log.Level[] sévérité = {
                jo.joide.workbench.BuildEvent.Log.Level.DEBUG,
                jo.joide.workbench.BuildEvent.Log.Level.INFO,
                jo.joide.workbench.BuildEvent.Log.Level.LIFECYCLE,
                jo.joide.workbench.BuildEvent.Log.Level.WARNING,
                jo.joide.workbench.BuildEvent.Log.Level.ERROR };
        for (int i = 1; i < sévérité.length; i++) {
            assertEquals(-1, sévérité[i - 1].compareTo(sévérité[i]),
                    "l'ordinal doit suivre la sévérité : " + sévérité[i - 1]
                            + " < " + sévérité[i]);
        }
    }

    @Test
    void downloadsTranslateToTheirOwnEventNeverToStringNoise() {
        BuildEvent.DownloadEvent téléchargement =
                new BuildEvent.DownloadEvent("b", 2L, "ui-android-1.7.1.aar",
                        4_500_000L, 4_500_000L, true);
        jo.joide.workbench.BuildEvent.Download traduit =
                (jo.joide.workbench.BuildEvent.Download)
                        ForgeEventTranslator.translate(téléchargement);
        assertEquals("ui-android-1.7.1.aar", traduit.resource());
        assertEquals(4_500_000L, traduit.bytesDownloaded());
        assertEquals(true, traduit.done());
    }

    @Test
    void heartbeatsTranslateToTheirOwnEvent() {
        BuildEvent.HeartbeatEvent pulsation =
                new BuildEvent.HeartbeatEvent("b", 3L, "app:dex",
                        "dexage : 12/41 bibliothèques", 45_000L);
        jo.joide.workbench.BuildEvent.Heartbeat traduit =
                (jo.joide.workbench.BuildEvent.Heartbeat)
                        ForgeEventTranslator.translate(pulsation);
        assertEquals("app:dex", traduit.task());
        assertEquals("dexage : 12/41 bibliothèques", traduit.message());
        assertEquals(45_000L, traduit.elapsedMs());
    }

    @Test
    void forgeRequestCarriesTheConsoleLevelAsAnEngineArgument() {
        // Le niveau de console est un ARGUMENT du moteur (les -q/-i/-d de
        // Gradle) : la requête Forge doit le porter jusqu'à BuildOptions.
        jo.forge.api.BuildRequest requête = ForgeBuildSession.forgeRequest("b1",
                "/projets/demo", false,
                jo.joide.workbench.BuildEvent.Log.Level.DEBUG,
                new ForgeEngineManager.DeviceProfile("arm64-v8a", 4096));
        assertEquals(jo.forge.api.BuildEvent.LogEvent.LogLevel.DEBUG,
                requête.options().consoleLevel());
        assertEquals(true, requête.options().verbose(),
                "verbose suit le niveau : DEBUG => bavard");

        jo.forge.api.BuildRequest essentielle = ForgeBuildSession.forgeRequest("b2",
                "/projets/demo", false,
                jo.joide.workbench.BuildEvent.Log.Level.LIFECYCLE,
                new ForgeEngineManager.DeviceProfile("arm64-v8a", 4096));
        assertEquals(jo.forge.api.BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                essentielle.options().consoleLevel());
        assertEquals(false, essentielle.options().verbose());
    }

    @Test
    void forgeRequestDefaultsToLifecycleAndKeepsTheDeviceProfile() {
        // Niveau absent (chips jamais touchées, requête historique) : LIFECYCLE,
        // la console de build classique ; l'ABI et le mode mémoire suivent.
        jo.forge.api.BuildRequest défaut = ForgeBuildSession.forgeRequest("b3",
                "/projets/demo", false, null,
                new ForgeEngineManager.DeviceProfile("arm64-v8a", 4096));
        assertEquals(jo.forge.api.BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                défaut.options().consoleLevel());
        assertEquals("arm64-v8a", défaut.options().abi());

        jo.forge.api.BuildRequest basse = ForgeBuildSession.forgeRequest("b4",
                "/projets/demo", false, null,
                new ForgeEngineManager.DeviceProfile("arm64-v8a", 2048));
        assertEquals(true, basse.options().lowMemory(),
                "sous 2,5 Go : le moteur reçoit le mode basse mémoire");
    }

    @Test
    void workbenchRequestDefaultsToLifecycle() {
        // La forme historique à trois champs reste LIFECYCLE : les appelants
        // d'avant le protocole 4 gardent la console de build classique.
        jo.joide.workbench.BuildRequest troisChamps =
                new BuildRequest("b5", "/projets/demo", false);
        assertEquals(jo.joide.workbench.BuildEvent.Log.Level.LIFECYCLE,
                troisChamps.consoleLevel());
    }
}
