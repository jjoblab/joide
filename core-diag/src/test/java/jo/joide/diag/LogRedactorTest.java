package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogRedactorTest {

    @Test
    void masqueLesCourriels() {
        assertEquals("courriel <courriel> autre <courriel>",
                LogRedactor.redact("courriel alice@exemple.fr autre bob@domaine.org"));
    }

    @Test
    void masqueLesUriFileEtCheminsAbsolus() {
        assertEquals("fichier file://<chemin>", LogRedactor.redact("fichier file:///data/user/0/x"));
        assertEquals("chemin <chemin> pas <chemin>",
                LogRedactor.redact("chemin /data/data/jo.joide.app pas /sdcard/JoIDE/encre"));
    }

    @Test
    void epargneLesUriHttp() {
        String url = "voir https://exemple.fr/chemin et http://autre.org";
        assertEquals(url, LogRedactor.redact(url));
    }

    @Test
    void hacheLIdentifiantDeDocumentContent() {
        String une = LogRedactor.redact("doc content://com.android.providers.downloads/documents/42");
        String deux = LogRedactor.redact("doc content://com.android.providers.downloads/documents/42");
        assertTrue(une.contains("content://com.android.providers.downloads/h-"));
        assertFalse(une.contains("/documents/42"));
        assertEquals(une, deux, "le hachage est déterministe — corrélable");
        String autre = LogRedactor.redact("doc content://com.android.providers.downloads/documents/43");
        assertFalse(une.substring(une.indexOf("h-"))
                .equals(autre.substring(autre.indexOf("h-"))));
    }

    @Test
    void estIdempotentSurLesUriDejaHachees() {
        String once = LogRedactor.redact("doc content://autorite/documents/42");
        assertEquals(once, LogRedactor.redact(once), "réexpurger ne déforme pas");
    }

    @Test
    void masqueLesSecretsDeFournisseurs() {
        assertEquals("<secret>",
                LogRedactor.redact("Authorization: Bearer ghp_11112222333344445555666677778888"));
        assertEquals("cle <secret>", LogRedactor.redact("cle sk-abc123def456ghi789xyz"));
        assertEquals("cle <secret>", LogRedactor.redact("cle AIzaSyA123456789012345678"));
        assertEquals("en-tête <secret>",
                LogRedactor.redact("en-tête x-api-key: abcdef1234567890abcdef"));
        assertEquals("jeton <secret> parti",
                LogRedactor.redact("jeton ghp_11112222333344445555666677778888 parti"));
    }

    @Test
    void nullEtVideBornés() {
        assertEquals("", LogRedactor.redact(null));
        assertEquals("", LogRedactor.redact(""));
    }

    @Test
    void texteSansDonneeResteInchange() {
        assertEquals("Build réussi en 4,2 s (12 tâches)", LogRedactor.redact("Build réussi en 4,2 s (12 tâches)"));
    }
}
