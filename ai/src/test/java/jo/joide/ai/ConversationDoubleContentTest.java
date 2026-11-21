package jo.joide.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 4 : conversations à double contenu — aller-retour complet (transcript
 * ET historique modèle avec appels d'outils), migration v1→v2, usage cumulé,
 * empreinte de projet stable.
 */
class ConversationDoubleContentTest {

    @TempDir
    Path temp;

    private ConversationStore store() {
        return new ConversationStore.JsonFiles(temp.resolve("assistant"));
    }

    @Test
    void roundTripsTranscriptAndExactModelHistory() throws IOException {
        ConversationStore store = store();
        List<Conversation.HistoryEntry> history = new ArrayList<>();
        history.add(Conversation.HistoryEntry.plain("user", "corrige l'erreur"));
        history.add(new Conversation.HistoryEntry("assistant", "je regarde",
                List.of(new Conversation.ToolCallData("a1", "lire_fichier",
                        "{\"chemin\":\"Main.java\"}")), null));
        history.add(new Conversation.HistoryEntry("tool",
                "1: public class Main {}\n", null, "a1"));
        Conversation conversation = Conversation.fresh("aa11-2233", 1_000L)
                .withMessage(new Conversation.Message("user", "corrige l'erreur"), 2_000L)
                .withMessage(new Conversation.Message("assistant", "voilà"), 3_000L)
                .withModel("openrouter", "exemple/a", 3_000L)
                .withEffort("eleve", 3_000L)
                .withUsage(Usage.of(100, 40), 3_000L)
                .withHistory(history, 3_000L);
        store.save(conversation);

        Conversation loaded = store.load("aa11-2233");

        // Transcript vu.
        assertEquals(2, loaded.messages().size());
        assertEquals("corrige l'erreur", loaded.messages().get(0).content());
        // Historique exact vu par le modèle (avec l'appel d'outil).
        assertEquals(3, loaded.history().size());
        Conversation.HistoryEntry call = loaded.history().get(1);
        assertEquals("a1", call.toolCalls().get(0).id());
        assertEquals("lire_fichier", call.toolCalls().get(0).name());
        assertEquals("{\"chemin\":\"Main.java\"}", call.toolCalls().get(0).argumentsJson());
        // Fournisseur, modèle, effort, usage épinglés.
        assertEquals("openrouter", loaded.providerId());
        assertEquals("exemple/a", loaded.model());
        assertEquals("eleve", loaded.effort());
        assertEquals(100, loaded.usage().promptTokens());
        // Rejeu au modèle : la traduction est fidèle.
        ConversationHistory.Entry replayed = Conversation.toHistory(call);
        assertEquals("a1", replayed.toolCalls().get(0).id());
        assertEquals("lire_fichier", replayed.toolCalls().get(0).name());
    }

    @Test
    void v1FilesMigrateToDoubleContentAtRead() throws IOException {
        Path dir = temp.resolve("assistant");
        Files.createDirectories(dir);
        // Fichier du Lot 0 : transcript seul, sans champ schema.
        String v1 = "{\"id\":\"bb22-3344\",\"title\":\"ancienne\","
                + "\"created\":100,\"updated\":200,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"question\"},"
                + "{\"role\":\"assistant\",\"content\":\"réponse\"}]}";
        Files.write(dir.resolve("bb22-3344.json"),
                v1.getBytes(StandardCharsets.UTF_8));

        Conversation loaded = store().load("bb22-3344");

        assertEquals("ancienne", loaded.title());
        assertEquals(2, loaded.messages().size());
        assertEquals(2, loaded.history().size(),
                "migration v1 : le transcript devient l'historique de départ");
        assertEquals("user", loaded.history().get(0).role());
        assertNull(loaded.providerId(), "v1 : fournisseur inconnu, jamais deviné");
    }

    @Test
    void usageAccumulatesAcrossTurns() {
        Conversation conversation = Conversation.fresh("cc33-4455", 1L)
                .withUsage(Usage.of(100, 40), 2L)
                .withUsage(new Usage(50, 10, 5, 0), 3L);
        assertEquals(150, conversation.usage().promptTokens());
        assertEquals(50, conversation.usage().completionTokens());
        assertEquals(5, conversation.usage().reasoningTokens());
        // Un usage inconnu ne perturbe pas le cumul.
        Conversation unchanged = conversation.withUsage(Usage.unknown(), 4L);
        assertEquals(150, unchanged.usage().promptTokens());
    }

    @Test
    void projectFingerprintIsStableAndSafeForDirectories() {
        String project = "/storage/emulated/0/JoIDE/projects/Mon Application";
        String fingerprint = ConversationStore.fingerprint(project);
        assertEquals(fingerprint, ConversationStore.fingerprint(project), "stable");
        assertTrue(fingerprint.matches("[0-9a-f]{24}"), "hexa, sûr pour un dossier");
        assertNotEquals(fingerprint,
                ConversationStore.fingerprint("/autre/projet"), "distinct par projet");
    }

    @Test
    void modelSwitchCarriesTheConversationIntact() {
        Conversation before = Conversation.fresh("dd44-5566", 1L)
                .withMessage(new Conversation.Message("user", "question"), 2L)
                .withHistory(List.of(
                        Conversation.HistoryEntry.plain("user", "question")), 2L)
                .withModel("openai", "m-a", 2L);
        Conversation after = before.withModel("openrouter", "exemple/b", 3L);
        assertEquals(1, after.messages().size(), "le transcript suit");
        assertEquals(1, after.history().size(), "l'historique modèle suit");
        assertEquals("exemple/b", after.model());
        assertEquals("m-a", before.model(), "l'original est intact (non modifiable)");
    }
}
