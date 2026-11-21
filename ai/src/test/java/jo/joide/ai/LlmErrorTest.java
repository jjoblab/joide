package jo.joide.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 1 : erreurs LLM typées — un statut HTTP devient sa cause (une action
 * suggérée chacune), Retry-After est lu, le corps est rédigé (jamais la clé).
 */
class LlmErrorTest {

    @Test
    void statusMapsToItsOwnKind() {
        assertEquals(LlmError.Kind.AUTH, LlmError.fromStatus(401, "bad key", null).kind());
        assertEquals(LlmError.Kind.AUTH, LlmError.fromStatus(403, "forbidden", null).kind());
        assertEquals(LlmError.Kind.INSUFFICIENT_CREDITS,
                LlmError.fromStatus(402, "no credits", null).kind());
        assertEquals(LlmError.Kind.MODEL_UNAVAILABLE,
                LlmError.fromStatus(404, "model not found", null).kind());
        assertEquals(LlmError.Kind.TIMEOUT, LlmError.fromStatus(408, "", null).kind());
        assertEquals(LlmError.Kind.CONTEXT_TOO_LONG,
                LlmError.fromStatus(413, "too large", null).kind());
        assertEquals(LlmError.Kind.SERVER, LlmError.fromStatus(503, "down", null).kind());
        assertEquals(LlmError.Kind.BAD_REQUEST,
                LlmError.fromStatus(400, "weird", null).kind());
    }

    @Test
    void rateLimitCarriesRetryAfter() {
        LlmError error = LlmError.fromStatus(429, "slow down", "42");
        assertEquals(LlmError.Kind.RATE_LIMIT, error.kind());
        assertEquals(42, error.retryAfterSeconds());
        LlmError noHeader = LlmError.fromStatus(429, "slow down", null);
        assertEquals(0, noHeader.retryAfterSeconds());
    }

    @Test
    void contextTooLongRecognizedIn400Body() {
        LlmError error = LlmError.fromStatus(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}",
                null);
        assertEquals(LlmError.Kind.CONTEXT_TOO_LONG, error.kind());
    }

    @Test
    void bodyIsRedactedAndBounded() {
        String key = "sk-or-v1-verylongkeyvalue123456";
        LlmError error = LlmError.fromStatus(401,
                "invalid key " + key + " " + "x".repeat(500), null);
        assertFalse(error.getMessage().contains(key));
        assertFalse(error.detail().contains(key));
        assertTrue(error.detail().length() <= 210, "le corps est borné");
    }

    @Test
    void eachKindHasTitleAndMostAnAction() {
        for (LlmError.Kind kind : LlmError.Kind.values()) {
            LlmError error = LlmError.of(kind, "détail");
            assertTrue(error.getMessage().startsWith(kind.title()));
            // L'annulation est la seule sans action proposée.
            if (kind != LlmError.Kind.CANCELLED) {
                assertFalse(kind.action() == null || kind.action().isEmpty(),
                        kind + " a une action suggérée");
            }
        }
    }

    @Test
    void usageRecordCarriesRealValues() {
        Usage usage = new Usage(100, 40, 12, 64);
        assertEquals(140, usage.totalTokens());
        assertFalse(usage.isUnknown());
        assertTrue(Usage.unknown().isUnknown());
        assertEquals(0, Usage.unknown().totalTokens());
    }
}
