package jo.joide.corestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import jo.joide.workbench.WorkMode;

/**
 * Stockage local du choix de mode : fichier propriétés écrit atomiquement
 * (temp + renommage), sans dépendance Android (testable sur JVM).
 */
public final class ModePreferences {

    private final Path file;

    public ModePreferences(Path file) {
        this.file = file;
    }

    /** Lit le choix persisté ; null si absent ou illisible (jamais d'exception vers l'appelant). */
    public synchronized ModeChoice read() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
            String mode = p.getProperty("mode");
            String installed = p.getProperty("installed", "false");
            if (mode == null) {
                return null;
            }
            return new ModeChoice(WorkMode.valueOf(mode), Boolean.parseBoolean(installed));
        } catch (Exception e) {
            return null;
        }
    }

    /** Écrit le choix de manière atomique (temp + renommage). */
    public synchronized void write(ModeChoice choice) throws IOException {
        Properties p = new Properties();
        p.setProperty("mode", choice.chosenMode().name());
        p.setProperty("installed", Boolean.toString(choice.modeInstalled()));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(writer, "JoIDE : choix de mode");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
