package jo.joide.corestore;

import jo.joide.workbench.ModeTravail;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Stockage local du choix de mode : fichier propriétés écrit atomiquement
 * (temp + renommage), sans dépendance Android (testable sur JVM).
 */
public final class PreferencesModes {

    private final Path fichier;

    public PreferencesModes(Path fichier) { this.fichier = fichier; }

    public synchronized ChoixMode lire() {
        if (!Files.isRegularFile(fichier)) return null;
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(fichier, StandardCharsets.UTF_8));
            String mode = p.getProperty("mode");
            String installe = p.getProperty("installe", "false");
            if (mode == null) return null;
            return new ChoixMode(ModeTravail.valueOf(mode), Boolean.parseBoolean(installe));
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void ecrire(ChoixMode choix) throws java.io.IOException {
        Properties p = new Properties();
        p.setProperty("mode", choix.modeChoisi().name());
        p.setProperty("installe", Boolean.toString(choix.modeInstalle()));
        Path tmp = fichier.resolveSibling(fichier.getFileName() + ".tmp");
        Files.createDirectories(fichier.getParent());
        try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "JoIDE : choix de mode");
        }
        Files.move(tmp, fichier, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
