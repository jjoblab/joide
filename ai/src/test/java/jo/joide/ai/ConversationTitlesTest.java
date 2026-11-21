package jo.joide.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lot 0 (séparation Assistant/Agent) : titre de conversation et nom de
 * projet dérivés du premier message — bornage du titre, CamelCase sans
 * mots grammaticaux ni diacritiques, repli honnête (chaîne vide).
 */
class ConversationTitlesTest {

    @Test
    void deriveTitleTakesFirstLineTruncated() {
        assertEquals("Expliquer les records", ConversationTitles.deriveTitle(
                "Expliquer les records\navec un exemple concret"));
        assertEquals("", ConversationTitles.deriveTitle(null));
        assertEquals("", ConversationTitles.deriveTitle("   \n  "));
        String long_ = "x".repeat(80);
        assertEquals(49, ConversationTitles.deriveTitle(long_).length(),
                "48 caractères + l'ellipse");
    }

    @Test
    void suggestProjectNameBuildsCamelCaseFromSignificantWords() {
        assertEquals("IdeeProjet", ConversationTitles.suggestProjectName("Idée de projet"));
        assertEquals("ApprendreJava", ConversationTitles.suggestProjectName("Apprendre Java"));
        assertEquals("ComprendreErreurBuild",
                ConversationTitles.suggestProjectName("Comprendre une erreur de build"));
        assertEquals("ExpliquerConcept",
                ConversationTitles.suggestProjectName("Expliquer un concept"));
    }

    @Test
    void suggestProjectNameSkipsGrammarWordsAndAccents() {
        assertEquals("CreerJeuPierre", ConversationTitles.suggestProjectName(
                "je voudrais créer un jeu avec la pierre"));
        assertEquals("", ConversationTitles.suggestProjectName("le la les un une"));
        assertEquals("", ConversationTitles.suggestProjectName(null));
    }

    @Test
    void suggestProjectNameCapsWordsAndLength() {
        String name = ConversationTitles.suggestProjectName(
                "construire application meteorologie interactive collaborative");
        assertEquals("ConstruireApplicationMeteorologie", name,
                "trois mots au plus");
    }
}
