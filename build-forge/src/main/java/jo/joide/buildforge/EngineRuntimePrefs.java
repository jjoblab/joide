package jo.joide.buildforge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistance des réglages du runtime de build (lot L10, page « Build
 * Runtime ») : {@link EngineSettings} dans un fichier Properties (même
 * mécanique que les préférences de mode — aucun stockage Android, testable
 * JVM). Lecture corrompue = défauts honnêtes (auto), jamais d'exception vers
 * l'appelant.
 */
public final class EngineRuntimePrefs {

    private final Path file;

    /** Préférences sur un fichier (appareil : filesDir/runtime.properties). */
    public EngineRuntimePrefs(Path file) {
        this.file = file;
    }

    /** Réglages lus ; défauts (tout auto) si absents ou illisibles. */
    public synchronized EngineSettings read() {
        EngineSettings auto = EngineSettings.auto();
        if (!Files.isRegularFile(file)) {
            return auto;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return auto;
        }
        int tas = entier(p, "heapMb", 0);
        EngineSettings.LowMemory mode = switch (
                p.getProperty("lowMemory", "AUTO").trim().toUpperCase()) {
            case "FORCE" -> EngineSettings.LowMemory.FORCÉ;
            case "OFF" -> EngineSettings.LowMemory.DÉSACTIVÉ;
            default -> EngineSettings.LowMemory.AUTO;
        };
        return new EngineSettings(tas, mode);
    }

    /** Écrit les réglages (atomicité best-effort : temp + renommage). */
    public synchronized void write(EngineSettings settings) throws IOException {
        Properties p = new Properties();
        p.setProperty("heapMb", String.valueOf(settings.heapMbOverride()));
        p.setProperty("lowMemory", settings.mode() == EngineSettings.LowMemory.FORCÉ
                ? "FORCE" : settings.mode() == EngineSettings.LowMemory.DÉSACTIVÉ
                ? "OFF" : "AUTO");
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            p.store(out, "JoIDE — runtime de build (lot L10)");
        }
        Files.move(temp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static int entier(Properties p, String clé, int défaut) {
        try {
            return Integer.parseInt(p.getProperty(clé, "").trim());
        } catch (NumberFormatException e) {
            return défaut;
        }
    }
}
