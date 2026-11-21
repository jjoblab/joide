package jo.joide.ai.json;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 1 : codec JSON interne — corpus d'échappements, Unicode, imbrication
 * profonde, nombres, aller-retour complet, erreurs lisibles.
 */
class JsonTest {

    @Test
    void roundTripsEscapesAndUnicode() throws JsonException {
        String source = "guillemet \" antislash \\ retour\\nà la ligne \\u20ac é à ✦";
        Map<String, Object> root = Map.of("texte", source, "liste", List.of("a\"b", "c\\\\d"));
        String json = Json.write(root);

        Map<String, Object> parsed = Json.parseObject(json);
        assertEquals(source, parsed.get("texte"));
        assertEquals(List.of("a\"b", "c\\\\d"), parsed.get("liste"));
    }

    @Test
    void parsesAllLiteralKinds() throws JsonException {
        Map<String, Object> parsed = Json.parseObject(
                "{\"entier\":42,\"negatif\":-7,\"decimal\":3.5,\"vrai\":true,"
                        + "\"faux\":false,\"nul\":null,\"vide\":{},\"tableau\":[1,\"deux\",null]}");
        assertEquals(42L, parsed.get("entier"));
        assertEquals(-7L, parsed.get("negatif"));
        assertEquals(3.5, parsed.get("decimal"));
        assertEquals(Boolean.TRUE, parsed.get("vrai"));
        assertEquals(Boolean.FALSE, parsed.get("faux"));
        assertEquals(null, parsed.get("nul"));
        assertTrue(((Map<?, ?>) parsed.get("vide")).isEmpty());
        assertEquals(3, ((List<?>) parsed.get("tableau")).size());
    }

    @Test
    void deepNestingSurvives() throws JsonException {
        StringBuilder json = new StringBuilder();
        int depth = 200;
        json.append("[".repeat(depth)).append("42").append("]".repeat(depth));
        Object parsed = Json.parse(json.toString());
        Object current = parsed;
        for (int i = 1; i < depth; i++) {
            current = ((List<?>) current).get(0);
        }
        assertEquals(42L, ((List<?>) current).get(0));
    }

    @Test
    void escapesControlCharactersOnWrite() throws JsonException {
        String json = Json.write("ligne1\u0007ligne2");
        assertEquals("\"ligne1\\u0007ligne2\"", json);
        assertEquals("ligne1\u0007ligne2", Json.parse(json));
    }

    @Test
    void invalidDocumentsFailWithPosition() {
        assertThrows(JsonException.class, () -> Json.parse("{"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2,"));
        assertThrows(JsonException.class, () -> Json.parse("\"chaîne non fermée"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1} excès"));
        assertThrows(JsonException.class, () -> Json.parse("pas du tout"));
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\"a\":\"\\u00zz\"}"));
        assertTrue(e.getMessage().contains("position"), "l'erreur porte sa position");
    }

    @Test
    void parseObjectRejectsNonObjectRoot() {
        assertThrows(JsonException.class, () -> Json.parseObject("[1,2]"));
        assertThrows(JsonException.class, () -> Json.parseObject("\"texte\""));
    }

    @Test
    void writePreservesKeyOrder() throws JsonException {
        String json = Json.write(Map.of("z", 1, "a", 2, "m", 3));
        // Map.of ne garantit pas l'ordre : on teste avec une LinkedHashMap.
        java.util.Map<String, Object> ordered = new java.util.LinkedHashMap<>();
        ordered.put("z", 1);
        ordered.put("a", 2);
        ordered.put("m", 3);
        String orderedJson = Json.write(ordered);
        assertTrue(orderedJson.indexOf("\"z\"") < orderedJson.indexOf("\"a\""));
        assertTrue(orderedJson.indexOf("\"a\"") < orderedJson.indexOf("\"m\""));
        assertEquals(3, Json.parseObject(json).size());
    }

    @Test
    void numbersRoundTripWithoutPrecisionLossForLongs() throws JsonException {
        long big = 1_700_000_000_000L;
        Map<String, Object> parsed = Json.parseObject("{\"instant\":" + big + "}");
        assertEquals(big, parsed.get("instant"));
    }
}
