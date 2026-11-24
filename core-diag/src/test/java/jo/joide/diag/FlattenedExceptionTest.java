package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlattenedExceptionTest {

    @Test
    void borneLesTranchesEtLesCauses() {
        RuntimeException deep = new RuntimeException("fond");
        Throwable current = deep;
        for (int i = 0; i < 30; i++) {
            current = new RuntimeException("niveau" + i, current);
        }
        FlattenedException flat = FlattenedException.from(current, 10, 4);
        assertEquals(10, flat.frames().size());
        int depth = 0;
        FlattenedException cause = flat.cause();
        while (cause != null) {
            depth++;
            cause = cause.cause();
        }
        assertEquals(4, depth);
    }

    @Test
    void surviveAuxCyclesDeCauses() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b); // a → b → a : cycle réel
        FlattenedException flat = FlattenedException.from(a, 20, 10);
        int depth = 0;
        FlattenedException cause = flat;
        while (cause != null) {
            depth++;
            cause = cause.cause();
        }
        assertTrue(depth <= 11, "la chaîne cyclique doit être coupée, profondeur=" + depth);
    }

    @Test
    void gardeLesExceptionsSupprimeesBornees() {
        try {
            try {
                throw new IllegalStateException("principale");
            } catch (IllegalStateException principal) {
                principal.addSuppressed(new IllegalArgumentException("s1"));
                principal.addSuppressed(new IllegalArgumentException("s2"));
                throw principal;
            }
        } catch (IllegalStateException avecSupprimees) {
            FlattenedException flat = FlattenedException.from(avecSupprimees);
            assertEquals(2, flat.suppressed().size());
            assertEquals("s1", flat.suppressed().get(0).message());
        }
    }

    @Test
    void transformMessagesParcourtTouteLaChaine() {
        Exception root = new RuntimeException("cle=sk-abc123def456ghi7");
        Exception top = new RuntimeException(" racine ", root);
        FlattenedException flat = FlattenedException.from(top);
        FlattenedException redacted = flat.transformMessages(
                message -> message == null ? null : LogRedactor.redact(message));
        assertEquals("racine", redacted.message().strip(), "le sommet n'a pas de secret");
        assertEquals("cle=<secret>", redacted.cause().message(), "la cause est expurgée");
    }

    @Test
    void formatAsTextRendLaTraceComplete() {
        Exception cause = new RuntimeException("cause profonde");
        Exception top = new IllegalStateException("sommet", cause);
        String text = FlattenedException.from(top).formatAsText();
        assertTrue(text.contains("java.lang.IllegalStateException : sommet"));
        assertTrue(text.contains("Causé par : java.lang.RuntimeException : cause profonde"));
        assertTrue(text.lines().anyMatch(line -> line.startsWith("  at ")));
    }

    @Test
    void nullsBornésEnConstruitVides() {
        FlattenedException flat = new FlattenedException("x", null, null, null, null);
        assertEquals(List.of(), flat.frames());
        assertEquals(List.of(), flat.suppressed());
        assertNull(flat.cause());
        assertNull(flat.message());
    }
}
