package jo.joide.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import jo.celeris.core.lsp.types.CompletionItemLsp;
import jo.celeris.core.lsp.types.CompletionListLsp;
import jo.celeris.core.lsp.types.DiagnosticLsp;
import jo.celeris.core.lsp.types.LspPosition;
import jo.celeris.core.text.Document;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

/**
 * Les conversions lsp4j → céleris (LOT-11) : chaque forme du protocole
 * traverse {@link LspTypes} — positions, diagnostics, complétion, survol —
 * et le vide du domaine reste le vide (convention I9 : une liste vide EST
 * une réponse).
 */
class LspTypesTest {

    /** Un document céleris de trois lignes (les bornes s'y vérifient). */
    private static Document document() {
        return Document.of("class A {\n  int x = 1;\n}\n");
    }

    @Test
    void positionDUnOffset() {
        Document doc = document();
        // « int » commence à la ligne 1, colonne 2.
        LspPosition position = LspTypes.position(doc, 12);
        assertEquals(1, position.line());
        assertEquals(2, position.column());
    }

    @Test
    void positionBornéeAuxExtrémités() {
        Document doc = document();
        assertEquals(new LspPosition(0, 0), LspTypes.position(doc, -5));
        LspPosition fin = LspTypes.position(doc, doc.length() + 50);
        assertEquals(doc.lineCount() - 1, fin.line());
    }

    @Test
    void offsetDUnePosition() {
        Document doc = document();
        assertEquals(12, LspTypes.offset(doc, new LspPosition(1, 2)));
        // Une colonne au-delà de la ligne se borne à la fin de ligne.
        assertEquals(doc.lineEnd(1), LspTypes.offset(doc, new LspPosition(1, 999)));
    }

    @Test
    void allerRetourPositionOffset() {
        Document doc = document();
        for (int offset = 0; offset < doc.length(); offset++) {
            LspPosition position = LspTypes.position(doc, offset);
            assertEquals(offset, LspTypes.offset(doc, position),
                    "aller-retour offset " + offset);
        }
    }

    @Test
    void diagnosticComplet() {
        Diagnostic source = new Diagnostic();
        source.setRange(new Range(new Position(0, 0), new Position(0, 5)));
        source.setSeverity(DiagnosticSeverity.Error);
        source.setMessage("Erreur de syntaxe");
        source.setCode(Either.<String, Integer>forLeft("E001"));
        source.setSource("ecj");
        DiagnosticLsp converti = LspTypes.diagnostic(source);
        assertEquals(1, converti.severity());
        assertEquals("Erreur de syntaxe", converti.message());
        assertEquals("E001", converti.code());
        assertEquals("ecj", converti.source());
        assertEquals(new LspPosition(0, 0), converti.range().start());
        assertEquals(new LspPosition(0, 5), converti.range().end());
    }

    @Test
    void diagnosticSansGravitéVautInformation() {
        Diagnostic source = new Diagnostic();
        source.setRange(new Range(new Position(2, 0), new Position(2, 1)));
        source.setMessage(" discret");
        assertEquals(3, LspTypes.diagnostic(source).severity());
    }

    @Test
    void listeDeComplétionGardeLOrdreDuServeur() {
        org.eclipse.lsp4j.CompletionList source =
                new org.eclipse.lsp4j.CompletionList();
        CompletionItem premier = new CompletionItem("ArrayList");
        premier.setKind(CompletionItemKind.Class);
        premier.setDetail("java.util.ArrayList");
        CompletionItem second = new CompletionItem("add");
        second.setKind(CompletionItemKind.Method);
        source.setItems(List.of(premier, second));
        CompletionListLsp convertie = LspTypes.completion(source);
        assertEquals(2, convertie.elements().size());
        CompletionItemLsp premierConverti = convertie.elements().get(0);
        assertEquals("ArrayList", premierConverti.label());
        assertEquals(CompletionItemKind.Class.getValue(), premierConverti.kind());
        assertEquals("java.util.ArrayList", premierConverti.detail());
        assertEquals("add", convertie.elements().get(1).label());
    }

    @Test
    void survolRicheSeConvertitEnTexte() {
        Hover source = new Hover();
        source.setContents(Either.forRight(
                new MarkupContent("markdown", "**int** — un entier 32 bits")));
        assertEquals("**int** — un entier 32 bits",
                LspTypes.hover(source).text());
    }

    @Test
    void survolVideRendNull() {
        Hover source = new Hover();
        source.setContents(Either.forRight(new MarkupContent("markdown", "  ")));
        assertNull(LspTypes.hover(source));
        assertNull(LspTypes.hover(null));
    }

    @Test
    void lesNullsSeConvertismentEnVides() {
        assertTrue(LspTypes.diagnostics(null).isEmpty());
        assertTrue(LspTypes.symbols(null).isEmpty());
        assertTrue(LspTypes.folds(null).isEmpty());
        assertTrue(LspTypes.edits(null).isEmpty());
        assertTrue(LspTypes.highlights(null).isEmpty());
        assertTrue(LspTypes.inlayHints(null).isEmpty());
        assertTrue(LspTypes.locations(null).isEmpty());
        assertNull(LspTypes.hover(null));
        assertNull(LspTypes.signature(null));
    }

    @Test
    void jetonsSémantiquesDécodésDuDelta() {
        org.eclipse.lsp4j.SemanticTokens source =
                new org.eclipse.lsp4j.SemanticTokens();
        // Un jeton ligne 0 colonne 0 longueur 5, un autre ligne 1 colonne 2.
        source.setData(List.of(0, 0, 5, 1, 0, 1, 2, 3, 2, 0));
        jo.celeris.core.lsp.types.SemanticTokensLsp convertis =
                LspTypes.tokens(source);
        assertEquals(2, convertis.tokens().size());
        assertEquals(5, convertis.tokens().get(0).length());
        assertEquals(1, convertis.tokens().get(1).line());
        assertEquals(2, convertis.tokens().get(1).column());
    }
}
