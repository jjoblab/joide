package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void ecritEtRelitUnArbreComplet() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("nom", "rapport");
        tree.put("nombre", 42L);
        tree.put("decimal", 1.5);
        tree.put("booleen", true);
        tree.put("nul", null);
        tree.put("liste", List.of("a", "b", 3L));
        Map<String, Object> enfant = new LinkedHashMap<>();
        enfant.put("profondeur", 2L);
        tree.put("enfant", enfant);

        String text = Json.write(tree);
        Object read = Json.parse(text);

        assertEquals(tree, read);
        assertEquals(42L, ((Map<?, ?>) read).get("nombre"));
        assertEquals(1.5, ((Map<?, ?>) read).get("decimal"));
    }

    @Test
    void echappeLesCaracteresDangereux() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("ligne", "a\"b\\c\nd\te");
        Object read = Json.parse(Json.write(tree));
        assertEquals("a\"b\\c\nd\te", ((Map<?, ?>) read).get("ligne"));
    }

    @Test
    void conserveLesAccentsEtEmojis() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("texte", "éréè 🎉 multilingue");
        Object read = Json.parse(Json.write(tree));
        assertEquals("éréè 🎉 multilingue", ((Map<?, ?>) read).get("texte"));
    }

    @Test
    void litLesEchappesUnicode() {
        Object read = Json.parse("{\"cle\":\"\\u00e9\\u0041\"}");
        assertEquals("éA", ((Map<?, ?>) read).get("cle"));
    }

    @Test
    void refuseLesDocumentsMalformes() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} reste"));
        assertThrows(Json.JsonException.class, () -> Json.parse("\"non ferme"));
        assertThrows(Json.JsonException.class, () -> Json.parse((String) null));
    }

    @Test
    void refuseLesProfondeursExcessives() {
        StringBuilder deep = new StringBuilder("[");
        deep.append("[".repeat(100));
        deep.append("]".repeat(100));
        deep.append("]");
        assertThrows(Json.JsonException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void litNombresNegatifsEtExposants() {
        Object read = Json.parse("{\"negatif\":-12,\"expo\":2e3}");
        assertEquals(-12L, ((Map<?, ?>) read).get("negatif"));
        assertEquals(2000.0, ((Map<?, ?>) read).get("expo"));
    }

    @Test
    void litBlancsEtObjetsVides() {
        assertNull(Json.parse("   null  "));
        assertEquals(Map.of(), Json.parse(" { } "));
        assertEquals(List.of(), Json.parse(" [] "));
        assertTrue(((List<?>) Json.parse(" [ 1 , 2 ] ")).size() == 2);
    }
}
