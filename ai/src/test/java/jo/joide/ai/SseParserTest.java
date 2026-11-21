package jo.joide.ai;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lot 1 : lecteur SSE — découpage des événements, lignes de commentaire,
 * CRLF, données multi-lignes, UTF-8 coupé entre deux lectures.
 */
class SseParserTest {

    private static List<String> feed(SseParser parser, String chunk) {
        byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
        return parser.feed(bytes, bytes.length);
    }

    @Test
    void simpleDataEventsEmitOnBlankLine() {
        SseParser parser = new SseParser();
        assertEquals(List.of(), feed(parser, "data: bonjour\n"));
        assertEquals(List.of("bonjour"), feed(parser, "\n"));
    }

    @Test
    void commentLinesAreIgnored() {
        SseParser parser = new SseParser();
        List<String> events = feed(parser, ": OPENROUTER PROCESSING\n\ndata: suite\n\n");
        assertEquals(List.of("suite"), events);
    }

    @Test
    void commentsThenDataEmitOnlyData() {
        SseParser parser = new SseParser();
        List<String> events = feed(parser,
                ": keep-alive\ndata: réel\n: autre commentaire\n\n");
        assertEquals(List.of("réel"), events);
    }

    @Test
    void crlfLineEndingsWork() {
        SseParser parser = new SseParser();
        assertEquals(List.of("a", "b"), feed(parser, "data: a\r\n\r\ndata: b\r\n\r\n"));
    }

    @Test
    void multiLineDataIsJoinedWithNewline() {
        SseParser parser = new SseParser();
        List<String> events = feed(parser, "data: ligne1\ndata: ligne2\n\n");
        assertEquals(List.of("ligne1\nligne2"), events);
    }

    @Test
    void spaceAfterColonIsOptional() {
        SseParser parser = new SseParser();
        assertEquals(List.of("compact"), feed(parser, "data:compact\n\n"));
    }

    @Test
    void utf8SplitAcrossChunksStaysIntact() {
        SseParser parser = new SseParser();
        String payload = "é✦€ caractère multi-octets";
        byte[] full = ("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
        List<String> events = List.of();
        // Coupe chaque octet : le pire cas de fragmentation réseau.
        for (byte b : full) {
            List<String> emitted = parser.feed(new byte[] {b}, 1);
            events = concat(events, emitted);
        }
        assertEquals(List.of(payload), events);
    }

    @Test
    void flushEmitsUnterminatedFinalEvent() {
        SseParser parser = new SseParser();
        feed(parser, "data: dernier");
        assertEquals(List.of("dernier"), parser.flush());
    }

    @Test
    void otherFieldsDoNotEmitEvents() {
        SseParser parser = new SseParser();
        assertEquals(List.of(), feed(parser, "event: message\nid: 7\nretry: 100\n\n"));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
