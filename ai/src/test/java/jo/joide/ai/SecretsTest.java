package jo.joide.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Lot 1 : rédaction des secrets — motifs connus des fournisseurs, clés
 * explicites, identifiants dans une URL. Une clé ne sort JAMAIS.
 */
class SecretsTest {

    @Test
    void masksKnownKeyFormats() {
        String redacted = Secrets.redact(
                "Erreur avec sk-or-v1-abc123def456ghi789 et sk-projet-0123456789abcdef"
                        + " puis ghp_0123456789abcdefghijklmnopqrstuvwxyz"
                        + " et AKIAIOSFODNN7EXAMPLE");
        assertFalse(redacted.contains("sk-or-v1-abc123def456ghi789"));
        assertFalse(redacted.contains("sk-projet-0123456789abcdef"));
        assertFalse(redacted.contains("ghp_0123456789abcdefghijklmnopqrstuvwxyz"));
        assertFalse(redacted.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void masksExplicitSecretAnywhere() {
        String key = "cle-explicite-tres-longue";
        String redacted = Secrets.redact("Authorization: Bearer " + key, key);
        assertFalse(redacted.contains(key));
    }

    @Test
    void keepsUrlHostButMasksPassword() {
        String redacted = Secrets.redact("connexion à https://utilisateur:MotDePasse@exemple.com/api");
        assertFalse(redacted.contains("MotDePasse"));
        assertEquals("connexion à https://utilisateur:••••@exemple.com/api", redacted);
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertEquals("rien à masquer ici", Secrets.redact("rien à masquer ici"));
        assertEquals(null, Secrets.redact(null));
    }
}
