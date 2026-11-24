package jo.joide.corestore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import jo.joide.workbench.WorkMode;
import jo.joide.diag.CrashReportSummary;
import jo.joide.diag.CrashType;
import jo.joide.diag.DeviceInfo;
import jo.joide.diag.LogEntry;
import jo.joide.diag.LogLevel;

/**
 * Lot L8 (mis à jour ADR-021 : CrashJournal remplacé par les rapports de
 * plantage complets de core-diag) : rapport de diagnostic avec secrets
 * rédigés + gestionnaire de modes (bascule, réparation, désinstallation) —
 * tests JVM purs sur répertoire temporaire. Les bornes du journal des
 * écroulements vivent désormais dans core-diag (CrashReportStoreTest).
 */
class L8StoreTest {

    @TempDir
    java.nio.file.Path tmp;

    // ---- DiagnosticReport : rédaction des secrets ---------------------------

    @Test
    void reportRedactsSecretsAndKeepsFieldNames() {
        assertEquals("apiKey=[rédigé]",
                DiagnosticReport.redact("apiKey=sk-abc123def456ghi789xyz"),
                "le jeton long près de key est rédigé, le champ reste");
        assertEquals("Authorization: Bearer=[rédigé]",
                DiagnosticReport.redact("Authorization: Bearer ghp_16charactersssss"),
                "un jeton bearer long est rédigé (Bearer est le champ capté, la valeur part)");
        assertEquals("secret=[rédigé]",
                DiagnosticReport.redact("secret: \"0123456789abcdef0123\""),
                "un secret long entre guillemets est rédigé (guillemets compris)");
        assertEquals("un chemin normal reste",
                DiagnosticReport.redact("un chemin normal reste"),
                "une ligne sans jeton est intacte");
        // Un « token » trop court (≤ 16) : pas rédigé (heuristique : > 16).
        assertEquals("token court inchangé : abc123",
                DiagnosticReport.redact("token court inchangé : abc123"));
    }

    @Test
    void reportRendersAllSectionsAndBoundedBuildLog() {
        DiagnosticReport report = new DiagnosticReport(
                new DiagnosticReport.Versions("0.1.0", "0.2.6", "8.11.1",
                        "céleris, code-lsp, LayoutLib"),
                List.of(new DiagnosticReport.ModeState("FORGE", "INSTALLED"),
                        new DiagnosticReport.ModeState("GRADLE", "NOT_INSTALLED")),
                "tâche compileJava\n" + "erreur line\n".repeat(300)
                        + "apiKey=sk-abcdef0123456789abcdef",
                List.of(new CrashReportSummary("id-1", CrashType.EXCEPTION,
                        System.currentTimeMillis(), "java.io.IOException",
                        "sortie interrompue", false)),
                List.of(new LogEntry(System.currentTimeMillis(), "session",
                        LogLevel.INFO, "Build", "main", "Build lancé (Forge)", null)),
                new DeviceInfo("Google", "Pixel 8", "15", 35, "arm64-v8a",
                        "fr-FR", 256_000_000L, 128_000_000L, 10_000_000_000L));
        String rendered = report.render();

        assertTrue(rendered.contains("Versions :"), "la section versions existe");
        assertTrue(rendered.contains("0.2.6"), "la version Forge est rendue");
        assertTrue(rendered.contains("FORGE : INSTALLED"), "l'état des modes est rendu");
        assertTrue(rendered.contains("Rapports de plantage"));
        assertTrue(rendered.contains("sortie interrompue"), "le plantage est rendu");
        assertTrue(rendered.contains("[non consulté]"), "l'état de lecture est rendu");
        assertTrue(rendered.contains("Journal applicatif"), "la queue du journal est rendue");
        assertTrue(rendered.contains("Build lancé (Forge)"));
        assertTrue(rendered.contains("Pixel 8"), "la photographie d'appareil est rendue");
        // Le journal de build est borné (queue gardée : l'erreur est à la fin).
        assertTrue(rendered.length() < 5_000, "le journal borné ne déborde pas");
        assertTrue(rendered.contains("[rédigé]"), "le secret du journal est rédigé");
        assertFalse(rendered.contains("sk-abcdef0123456789abcdef"),
                "la valeur du secret ne figure jamais dans le rapport");
    }

    // ---- ModeManager --------------------------------------------------------

    @Test
    void modeSwitchRequiresInstallationFirst() throws Exception {
        ModePreferences preferences = new ModePreferences(tmp.resolve("prefs.properties"));
        preferences.write(new ModeChoice(WorkMode.FORGE, true));
        ModeManager manager = new ModeManager(tmp.resolve("modes"), preferences);
        manager.markInstalled(WorkMode.FORGE);

        // GRADLE non installé : la bascule refuse honnêtement.
        assertThrows(IllegalStateException.class, () -> manager.switchTo(WorkMode.GRADLE));
        assertEquals(WorkMode.FORGE, preferences.read().chosenMode());
    }

    @Test
    void modeSwitchToInstalledModeWorks() throws Exception {
        ModePreferences preferences = new ModePreferences(tmp.resolve("prefs.properties"));
        preferences.write(new ModeChoice(WorkMode.FORGE, true));
        ModeManager manager = new ModeManager(tmp.resolve("modes"), preferences);
        manager.markInstalled(WorkMode.FORGE);
        manager.modeDirectory(WorkMode.GRADLE);
        manager.markInstalled(WorkMode.GRADLE);

        manager.switchTo(WorkMode.GRADLE);
        assertEquals(WorkMode.GRADLE, preferences.read().chosenMode(),
                "la bascule persiste le nouveau mode");
        assertTrue(manager.isInstalled(WorkMode.GRADLE));
    }

    @Test
    void uninstallActiveModeFallsBackToOtherUninstalled() throws Exception {
        ModePreferences preferences = new ModePreferences(tmp.resolve("prefs.properties"));
        preferences.write(new ModeChoice(WorkMode.FORGE, true));
        ModeManager manager = new ModeManager(tmp.resolve("modes"), preferences);
        manager.markInstalled(WorkMode.FORGE);

        manager.uninstall(WorkMode.FORGE);
        ModeChoice after = preferences.read();
        assertEquals(WorkMode.GRADLE, after.chosenMode(),
                "la désinstallation du mode actif repose le choix sur l'autre mode");
        assertFalse(after.modeInstalled(), "non installé : l'assistant reprend honnêtement");
        assertFalse(manager.isInstalled(WorkMode.FORGE));
        assertFalse(Files.isDirectory(tmp.resolve("modes/forge")),
                "le dossier du mode désinstallé part");
    }

    @Test
    void repairResetsDirectoryAndState() throws Exception {
        ModePreferences preferences = new ModePreferences(tmp.resolve("prefs.properties"));
        preferences.write(new ModeChoice(WorkMode.FORGE, true));
        ModeManager manager = new ModeManager(tmp.resolve("modes"), preferences);
        Path dir = manager.modeDirectory(WorkMode.FORGE);
        Files.writeString(dir.resolve("composant-casse.txt"), "casse");
        manager.markInstalled(WorkMode.FORGE);

        manager.repair(WorkMode.FORGE);
        assertFalse(Files.exists(dir.resolve("composant-casse.txt")),
                "la réparation repart d'un dossier propre");
        assertFalse(preferences.read().modeInstalled(),
                "la réparation remet l'état à installer (l'hôte relance l'installateur)");
    }

    @Test
    void occupiedBytesReportsHonestSize() throws Exception {
        ModePreferences preferences = new ModePreferences(tmp.resolve("prefs.properties"));
        preferences.write(new ModeChoice(WorkMode.FORGE, true));
        ModeManager manager = new ModeManager(tmp.resolve("modes"), preferences);
        Path dir = manager.modeDirectory(WorkMode.FORGE);
        Files.writeString(dir.resolve("moteur.bin"), "0".repeat(1_000));
        assertTrue(manager.occupiedBytes(WorkMode.FORGE) >= 1_000,
                "la taille affichée est constatée, jamais inventée");
        assertEquals(0, manager.occupiedBytes(WorkMode.GRADLE));
    }

    @Test
    void managerWithoutChoiceRefusesSilently() {
        ModePreferences preferences = new ModePreferences(tmp.resolve("prefs.properties"));
        ModeManager manager = new ModeManager(tmp.resolve("modes"), preferences);
        assertThrows(java.util.NoSuchElementException.class,
                () -> manager.switchTo(WorkMode.FORGE),
                "aucun mode caché par défaut : l'assistant est exigé (ADR-002)");
    }
}
