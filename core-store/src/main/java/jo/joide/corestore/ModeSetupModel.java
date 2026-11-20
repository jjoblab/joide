package jo.joide.corestore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import jo.joide.workbench.WorkMode;

/**
 * Modèle d'état de l'assistant des modes (ADR-002) : un seul état, testable sur
 * JVM sans Android, consommé par les fragments de la coquille. Règles :
 * premier lancement → assistant plein écran (aucun défaut caché) ; choix persisté
 * atomiquement ; le mode peut changer plus tard (paramètres, L8) et l'assistant
 * se représente si la persistance est perdue.
 *
 * <p>Le mode installé est distinct du mode choisi : on peut choisir Gradle et
 * l'installer demain ; les capacités et l'état d'installation viennent du backend
 * concerné, jamais devinés ici.</p>
 */
public final class ModeSetupModel {

    private final ModePreferences preferences;
    private final Path file;
    private WorkMode selected;

    /**
     * Nouveau modèle sur un fichier de préférences.
     *
     * @param preferencesFile fichier des choix ({@code modes.properties}).
     */
    public ModeSetupModel(Path preferencesFile) {
        this.file = Objects.requireNonNull(preferencesFile);
        this.preferences = new ModePreferences(preferencesFile);
    }

    /** Page courante de l'assistant (0 bienvenue, 1 Forge, 2 Gradle, 3 confirmation). */
    public enum Page { WELCOME, FORGE_PAGE, GRADLE_PAGE, CONFIRM }

    /** État consultable par la coquille (jamais d'état muet). */
    public record SetupState(boolean choiceMade, WorkMode selectedMode, Page currentPage) {

        public SetupState {
            Objects.requireNonNull(currentPage);
        }
    }

    /** État initial : assistant requis si aucun choix n'est persisté (premier lancement). */
    public boolean isSetupRequired() {
        return preferences.read() == null;
    }

    /** État courant de l'assistant (page et sélection provisoire). */
    public SetupState currentState() {
        ModeChoice choice = preferences.read();
        if (choice != null) {
            return new SetupState(true, choice.chosenMode(), Page.CONFIRM);
        }
        return new SetupState(false, selected, selected == WorkMode.GRADLE
                ? Page.GRADLE_PAGE : Page.WELCOME);
    }

    /** Sélection provisoire (carte touchée) ; persistée seulement à la confirmation. */
    public void select(WorkMode mode) {
        this.selected = Objects.requireNonNull(mode);
    }

    /**
     * Confirme le choix : persiste le mode choisi, non-installé par défaut
     * (l'installation est un acte séparé, piloté par l'assistant d'installation L8).
     *
     * @throws IOException si la persistance échoue (l'appelant affiche, ne devine pas).
     */
    public ModeChoice confirmChoice() throws IOException {
        WorkMode confirmed = selected != null ? selected : WorkMode.FORGE;
        ModeChoice choice = new ModeChoice(confirmed, false);
        preferences.write(choice);
        return choice;
    }

    /** Choix persisté, s'il existe (paramètres, bascule de mode L8). */
    public Optional<ModeChoice> persistedChoice() {
        return Optional.ofNullable(preferences.read());
    }

    /** Efface le choix (retour de l'assistant : réinitialisation explicite, L8). */
    public void reset() throws IOException {
        java.nio.file.Files.deleteIfExists(file);
    }
}
