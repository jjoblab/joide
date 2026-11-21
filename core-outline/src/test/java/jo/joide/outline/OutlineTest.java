package jo.joide.outline;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 5 du tiroir : plan du fichier actif — Java (types, membres,
 * visibilité, imports repliés), XML (éléments avec android:id), Markdown
 * (titres), JSON (clés). Tolérant au code incomplet : jamais d'échec.
 */
class OutlineTest {

    @Test
    void javaOutlineTypesMembersAndVisibility() {
        String source = """
                package com.example.app;

                import java.util.List;
                import java.util.Map;

                public abstract class MainActivity extends BaseActivity {
                  private static final Logger logger = Logger.getLogger();

                  public MainActivity(Bundle bundle) {
                  }

                  + champ errone mais tolere

                  @Override
                  public void onCreate(Bundle savedInstanceState) {
                  }

                  protected abstract void attachUi();

                  private List<String> names() {
                    return List.of();
                  }

                  class Holder {
                    public final View itemView;
                  }
                }
                """;
        List<Outline.Symbol> symbols = Outline.javaOutline(source);
        // package, imports repliés, classe, champ logger, constructeur,
        // onCreate, attachUi, names, Holder imbriqué, itemView.
        assertEquals(Outline.Kind.PACKAGE, symbols.get(0).kind());
        assertEquals("com.example.app", symbols.get(0).name());
        assertEquals(Outline.Kind.IMPORTS, symbols.get(1).kind());
        assertEquals("2 imports", symbols.get(1).name());
        assertEquals(Outline.Kind.CLASS, symbols.get(2).kind());
        assertEquals("MainActivity", symbols.get(2).name());
        assertEquals('-', symbols.get(3).visibility(), "logger est privé");
        assertTrue(symbols.get(3).statik(), "logger est statique");
        assertEquals(Outline.Kind.CONSTRUCTOR, symbols.get(4).kind());
        assertEquals("(Bundle)", symbols.get(4).signature());
        assertEquals(Outline.Kind.METHOD, symbols.get(5).kind());
        assertEquals("onCreate", symbols.get(5).name());
        assertEquals('+', symbols.get(5).visibility());
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("attachUi")
                && s.kind() == Outline.Kind.METHOD));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("Holder")
                && s.depth() == 1), "Holder est imbriqué dans MainActivity");
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("itemView")
                && s.kind() == Outline.Kind.FIELD));
    }

    @Test
    void javaOutlineToleratesIncompleteCode() {
        String broken = "class Brol {\n  void pause(\n"; // méthode non fermée.
        List<Outline.Symbol> symbols = Outline.javaOutline(broken);
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("Brol")));
        // Aucune exception, le plan partiel reste honnête.
    }

    @Test
    void xmlOutlineElementsWithId() {
        String layout = """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout android:id="@+id/root">
                  <TextView android:id="@id/titre" />
                  <Button android:id="@+id/envoyer"></Button>
                </LinearLayout>
                """;
        List<Outline.Symbol> symbols = Outline.xmlOutline(layout);
        assertEquals(3, symbols.size(), "la déclaration <?xml ?> ne compte pas");
        assertEquals("LinearLayout", symbols.get(0).name());
        assertEquals("@root", symbols.get(0).signature());
        assertEquals("TextView", symbols.get(1).name());
        assertEquals(1, symbols.get(1).depth(), "TextView est imbriqué");
        assertEquals("@titre", symbols.get(1).signature());
        assertEquals("Button", symbols.get(2).name());
    }

    @Test
    void markdownAndJsonOutlines() {
        List<Outline.Symbol> headings = Outline.markdownOutline(
                "# Titre\n\ntexte\n## Section\n### Sous-section\n");
        assertEquals(3, headings.size());
        assertEquals(0, headings.get(0).depth());
        assertEquals(1, headings.get(1).depth());
        assertEquals(2, headings.get(2).depth());

        List<Outline.Symbol> keys = Outline.jsonOutline(
                "{\n  \"name\": \"JoIDE\",\n  \"build\": {\n    \"version\": 2\n  }\n}\n");
        assertEquals(3, keys.size());
        assertEquals("name", keys.get(0).name());
        assertEquals(0, keys.get(0).depth());
        assertEquals("version", keys.get(2).name());
        assertEquals(1, keys.get(2).depth(), "version est imbriqué dans build");
    }
}
