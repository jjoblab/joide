package jo.joide.ai;

import java.util.List;
import jo.joide.ai.markdown.MarkdownParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 5 : rendu et interprétation — corpus de réponses types (titres,
 * listes, tableaux, blocs de code avec cible, diffs, raisonnements inline,
 * fences non fermées, fragments parasites) et **propriété de préfixes** :
 * pour chaque préfixe d'une réponse, le parseur et l'interprète ne lèvent
 * aucune exception et le document complet donne toujours le même résultat.
 */
class ResponseInterpreterTest {

    private static final String FULL_ANSWER = """
            ## Correction

            J'ai ajouté la vérification **nulle** (le résultat compte, un build la vérifie).

            - lecture du fichier
            - [x] remplacement ciblé
            - [ ] build de contrôle

            ```java title="app/src/main/java/…/Main.java"
            Objects.requireNonNull(name, "name");
            ```

            ```diff
            @@ -18,1 +18,2 @@
            - greet(name);
            + Objects.requireNonNull(name);
            + greet(name);
            ```

            ```bash
            ./gradlew :app:compileJava
            ```

            | Cas | Statut |
            |---|---|
            | Nom nul | corrigé |

            <think>Je vérifie d'abord la ligne concernée.</think>

            ```suivi
            Écrire un test pour greet
            Enchaîner sur le formulaire
            ```
            """;

    @Test
    void interpretsAllBlockKinds() {
        ResponseInterpreter.Interpreted interpreted =
                ResponseInterpreter.interpret(FULL_ANSWER);

        // Raisonnement retiré de la réponse, rendu à part.
        assertEquals("Je vérifie d'abord la ligne concernée.", interpreted.reasoning());
        assertFalse(interpreted.blocks().stream().anyMatch(b ->
                b instanceof MarkdownParser.Block.Paragraph p
                        && p.text().contains("<think>")));

        // Blocs typés.
        assertEquals(3, interpreted.codeBlocks().size());
        ResponseInterpreter.InterpretedCode code = interpreted.codeBlocks().get(0);
        assertEquals(ResponseInterpreter.InterpretedCode.Kind.CODE, code.kind());
        assertEquals("app/src/main/java/…/Main.java", code.targetPath());
        assertEquals(ResponseInterpreter.InterpretedCode.Kind.DIFF,
                interpreted.codeBlocks().get(1).kind());
        assertEquals(ResponseInterpreter.InterpretedCode.Kind.COMMAND,
                interpreted.codeBlocks().get(2).kind());

        // Tableau reconnu (une rangée).
        assertTrue(interpreted.blocks().stream().anyMatch(b ->
                b instanceof MarkdownParser.Block.Table t
                        && t.headers().contains("Cas") && t.rows().size() == 1));

        // Propositions : une par ligne, jamais de bloc suivi en brut.
        assertEquals(List.of("Écrire un test pour greet",
                "Enchaîner sur le formulaire"), interpreted.suggestions());
        assertFalse(interpreted.blocks().stream().anyMatch(b ->
                b instanceof MarkdownParser.Block.CodeBlock c
                        && "suivi".equals(c.language())));
    }

    @Test
    void reasoningTagUnclosedAtStreamEndStaysReasoning() {
        // Flux en cours : <think> ouvert, pas encore fermé.
        ResponseInterpreter.Interpreted partial =
                ResponseInterpreter.interpret("Réponse…\n<think>je réfléchis encore");
        assertEquals("je réfléchis encore", partial.reasoning());
        assertTrue(partial.blocks().stream().noneMatch(b ->
                b.toString().contains("think")));
    }

    @Test
    void leaksAndOrphansAreCleaned() {
        ResponseInterpreter.Interpreted interpreted = ResponseInterpreter.interpret(
                "Voici.\n\n\n\n{\"name\":\"lire_fichier\"}\nSuite du texte.");
        String document = blocksAsText(interpreted);
        assertFalse(document.contains("{\"name\""), "le JSON d'outil fuité disparaît");
        assertFalse(document.contains("\n\n\n"), "lignes vides normalisées");
    }

    @Test
    void unclosedFenceAtStreamEndRendersProvisionalCode() {
        ResponseInterpreter.Interpreted partial =
                ResponseInterpreter.interpret("Explication\n```java\ncode en cours");
        assertEquals(1, partial.codeBlocks().size());
        assertFalse(partial.codeBlocks().get(0).block().closed(),
                "fence ouverte : bloc provisoire, rendu correct");
        // Le même contenu fermé donne un bloc complet.
        ResponseInterpreter.Interpreted full =
                ResponseInterpreter.interpret("Explication\n```java\ncode en cours\n```");
        assertTrue(full.codeBlocks().get(0).block().closed());
    }

    @Test
    void cleanupNeverBreaksACodeBlock() {
        String withBlankLinesInCode = "Avant\n```java\n\nint a;\n\n\nint b;\n```\nAprès";
        ResponseInterpreter.Interpreted interpreted =
                ResponseInterpreter.interpret(withBlankLinesInCode);
        ResponseInterpreter.InterpretedCode code = interpreted.codeBlocks().get(0);
        assertTrue(code.block().code().contains("int a;"));
        assertTrue(code.block().code().contains("int b;"));
        assertTrue(code.block().code().contains("\n\n"),
                "les lignes vides DANS le bloc de code restent intactes");
    }

    @Test
    void diffWithoutLanguageTagIsStillDetected() {
        ResponseInterpreter.Interpreted interpreted = ResponseInterpreter.interpret(
                "```\n--- a/Main.java\n+++ b/Main.java\n@@ -1 +1 @@\n-x\n+y\n```");
        assertEquals(ResponseInterpreter.InterpretedCode.Kind.DIFF,
                interpreted.codeBlocks().get(0).kind(),
                "en-têtes ---/+++/@@ : diff même sans langage");
    }

    @Test
    void targetPathFromLeadingCommentWhenNoTitle() {
        ResponseInterpreter.Interpreted interpreted = ResponseInterpreter.interpret(
                "```java\n// app/src/test/java/…/MainTest.java\n@Test void t() {}\n```");
        assertEquals("app/src/test/java/…/MainTest.java",
                interpreted.codeBlocks().get(0).targetPath());
    }

    /** Propriété de préfixes : aucun préfixe ne casse, le final est stable. */
    @Test
    void everyPrefixOfARealAnswerParsesWithoutException() {
        for (int cut = 0; cut <= FULL_ANSWER.length(); cut += 7) {
            String prefix = FULL_ANSWER.substring(0,
                    Math.min(cut, FULL_ANSWER.length()));
            ResponseInterpreter.Interpreted partial =
                    ResponseInterpreter.interpret(prefix);
            MarkdownParser.parse(prefix);
            // Aucune exception, et le raisonnement détecté ne fuit jamais
            // dans les blocs rendus.
            if (partial.reasoning() != null) {
                assertFalse(blocksAsText(partial).contains("<think>"));
            }
        }
        // Document complet : le résultat ne dépend pas du découpage.
        ResponseInterpreter.Interpreted whole =
                ResponseInterpreter.interpret(FULL_ANSWER);
        assertEquals(3, whole.codeBlocks().size());
        assertEquals(2, whole.suggestions().size());
    }

    @Test
    void markdownParserCoreBlocks() {
        List<MarkdownParser.Block> blocks = MarkdownParser.parse(
                "# Titre\n\nParagraphe avec **gras** et `code`.\n\n> citation\n\n---\n"
                        + "1. premier\n2. deuxième\n- puce\n- [ ] à faire\n");
        assertTrue(blocks.get(0) instanceof MarkdownParser.Block.Heading h && h.level() == 1);
        assertTrue(blocks.get(1) instanceof MarkdownParser.Block.Paragraph p
                && p.text().contains("**gras**"));
        assertTrue(blocks.get(2) instanceof MarkdownParser.Block.Quote);
        assertTrue(blocks.get(3) instanceof MarkdownParser.Block.Separator);
        assertTrue(blocks.get(4) instanceof MarkdownParser.Block.ListItem item1
                && item1.ordered() && item1.text().equals("premier"));
        assertTrue(blocks.get(5) instanceof MarkdownParser.Block.ListItem item2
                && item2.ordered() && item2.text().equals("deuxième"));
        assertTrue(blocks.get(6) instanceof MarkdownParser.Block.ListItem bullet
                && !bullet.ordered() && !bullet.checkbox());
        assertTrue(blocks.get(7) instanceof MarkdownParser.Block.ListItem todo
                && todo.checkbox() && !todo.checked());
    }

    @Test
    void emptyAndNullDocumentsAreSafe() {
        assertTrue(MarkdownParser.parse(null).isEmpty());
        assertTrue(MarkdownParser.parse("").isEmpty());
        ResponseInterpreter.Interpreted empty = ResponseInterpreter.interpret(null);
        assertNull(empty.reasoning());
        assertTrue(empty.blocks().isEmpty());
        assertTrue(empty.suggestions().isEmpty());
    }

    private static String blocksAsText(ResponseInterpreter.Interpreted interpreted) {
        StringBuilder text = new StringBuilder();
        for (MarkdownParser.Block block : interpreted.blocks()) {
            text.append(block.toString()).append('\n');
        }
        return text.toString();
    }
}
