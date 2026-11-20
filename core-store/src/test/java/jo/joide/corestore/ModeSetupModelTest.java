package jo.joide.corestore;

import java.nio.file.Path;
import java.util.Optional;
import jo.joide.workbench.WorkMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * États de l'assistant des modes (critère d'acceptation L2) : premier lancement
 * → assistant sans défaut caché ; sélection provisoire ≠ choix persisté ;
 * confirmation persiste ; réinitialisation ramène l'assistant.
 */
class ModeSetupModelTest {

    @TempDir
    Path folder;

    private ModeSetupModel newModel() {
        return new ModeSetupModel(folder.resolve("modes.properties"));
    }

    @Test
    void firstLaunchRequiresSetupWithNoHiddenDefault() {
        ModeSetupModel model = newModel();
        assertTrue(model.isSetupRequired(), "premier lancement : assistant requis");
        ModeSetupModel.SetupState state = model.currentState();
        assertFalse(state.choiceMade());
        // Aucun mode n'est présélectionné en coulisse : la sélection est explicite
        // (selected nul → la page d'accueil, pas une carte graduée).
        assertNull(new ModePreferencesView(model).selectedOrNull());
        assertEquals(ModeSetupModel.Page.WELCOME, state.currentPage());
    }

    @Test
    void selectionIsProvisionalUntilConfirmed() throws Exception {
        ModeSetupModel model = newModel();
        model.select(WorkMode.GRADLE);
        ModeSetupModel.SetupState state = model.currentState();
        assertFalse(state.choiceMade(), "la sélection provisoire ne persiste rien");
        assertTrue(model.persistedChoice().isEmpty());
        assertTrue(model.isSetupRequired());
    }

    @Test
    void confirmationPersistsChosenModeNotInstalled() throws Exception {
        ModeSetupModel model = newModel();
        model.select(WorkMode.FORGE);
        ModeChoice choice = model.confirmChoice();
        assertEquals(WorkMode.FORGE, choice.chosenMode());
        // Choisi ≠ installé : l'installation est un acte séparé (L8).
        assertFalse(choice.modeInstalled());
        assertFalse(model.isSetupRequired());
        Optional<ModeChoice> persisted = model.persistedChoice();
        assertEquals(WorkMode.FORGE, persisted.orElseThrow().chosenMode());
    }

    @Test
    void resetBringsSetupBack() throws Exception {
        ModeSetupModel model = newModel();
        model.select(WorkMode.GRADLE);
        model.confirmChoice();
        assertFalse(model.isSetupRequired());
        model.reset();
        assertTrue(model.isSetupRequired(), "réinitialisation : assistant de retour");
        assertTrue(model.persistedChoice().isEmpty());
    }

    /** Lecture brute de la sélection provisoire (sans dévier le contrat public). */
    private record ModePreferencesView(ModeSetupModel model) {
        WorkMode selectedOrNull() {
            var state = model.currentState();
            return state.choiceMade() ? state.selectedMode() : null;
        }
    }
}
