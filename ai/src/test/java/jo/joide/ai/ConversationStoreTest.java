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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 0 (séparation Assistant/Agent) : stockage des conversations de
 * l'assistant — écriture atomique, relecture identique (échappements,
 * Unicode, sauts de ligne), ordre de la liste (plus récentes d'abord),
 * fichiers corrompus ignorés avec avertissement, identifiants validés
 * (traversée de dossier impossible), suppression.
 */
class ConversationStoreTest {

    @TempDir
    Path temp;

    private ConversationStore store(List<String> warnings) {
        return new ConversationStore.JsonFiles(temp.resolve("assistant"),
                warnings::add);
    }

    private static Conversation sample(String id, String title) {
        Conversation conversation = Conversation.fresh(id, 1_000L);
        conversation = conversation.withMessage(
                new Conversation.Message("user", "Expliquer « record » en Java\navec un exemple"), 2_000L);
        conversation = conversation.withMessage(
                new Conversation.Message("assistant", "Un record est une classe \"compacte\" \\o/ é à ç"), 3_000L);
        return conversation.withTitle(title, 3_000L);
    }

    @Test
    void saveThenLoadRoundTripsMessagesExactly() throws IOException {
        ConversationStore store = store(new ArrayList<>());
        Conversation conversation = sample("1a2b-3c4d", "Titre avec accents éè");
        store.save(conversation);

        Conversation loaded = store.load("1a2b-3c4d");

        assertEquals(conversation.id(), loaded.id());
        assertEquals(conversation.title(), loaded.title());
        assertEquals(conversation.createdAt(), loaded.createdAt());
        assertEquals(conversation.updatedAt(), loaded.updatedAt());
        assertEquals(conversation.messages(), loaded.messages(),
                "guillemets, antislash, accents et saut de ligne survivent au JSON");
    }

    @Test
    void saveIsAtomicAndReplacesPreviousVersion() throws IOException {
        ConversationStore store = store(new ArrayList<>());
        Conversation conversation = sample("1a2b-3c4d", "v1");
        store.save(conversation);
        store.save(conversation.withMessage(
                new Conversation.Message("user", "suite"), 4_000L));

        assertEquals(3, store.load("1a2b-3c4d").messages().size());
        assertEquals(1, filesEndingBy(".json").size(), "un seul fichier par conversation");
        assertTrue(filesEndingBy(".json.tmp").isEmpty(), "aucun temporaire oublié");
    }

    @Test
    void listOrdersMostRecentFirstAndCountsMessages() throws IOException {
        ConversationStore store = store(new ArrayList<>());
        store.save(sample("aaaa-0001", "ancienne"));
        store.save(sample("bbbb-0002", "récente").withTitle("récente", 9_000L));

        List<ConversationStore.Summary> summaries = store.list();

        assertEquals(2, summaries.size());
        assertEquals("bbbb-0002", summaries.get(0).id(), "la plus récemment modifiée d'abord");
        assertEquals("récente", summaries.get(0).title());
        assertEquals(2, summaries.get(0).messageCount());
    }

    @Test
    void listOnMissingDirectoryIsEmpty() throws IOException {
        assertTrue(store(new ArrayList<>()).list().isEmpty());
    }

    @Test
    void corruptFileIsIgnoredWithWarningNeverCrash() throws IOException {
        List<String> warnings = new ArrayList<>();
        ConversationStore store = store(warnings);
        store.save(sample("aaaa-0001", "saine"));
        Files.write(temp.resolve("assistant").resolve("0fff-0009.json"),
                "{\"id\":\"0fff-0009\",\"title\":\"cassée".getBytes(StandardCharsets.UTF_8));

        List<ConversationStore.Summary> summaries = store.list();

        assertEquals(1, summaries.size(), "seule la conversation saine est listée");
        assertEquals(1, warnings.size(), "le fichier corrompu est signalé");
        assertNull(store.load("0fff-0009"), "le chargement direct rend null, pas un plantage");
    }

    @Test
    void loadMissingConversationIsNull() throws IOException {
        assertNull(store(new ArrayList<>()).load("ffff-0000"));
    }

    @Test
    void identifiersAreValidatedBeforeAnyPathResolution() throws IOException {
        ConversationStore store = store(new ArrayList<>());

        assertThrows(IOException.class, () -> store.load("../escape"));
        assertThrows(IOException.class, () -> store.load("a/b"));
        assertThrows(IOException.class, () -> store.load(""));
        assertThrows(IOException.class, () -> store.delete(".."));
        assertFalse(java.nio.file.Files.exists(temp.resolve("escape.json")),
                "aucune écriture hors du dossier des conversations");
    }

    @Test
    void deleteRemovesTheFile() throws IOException {
        ConversationStore store = store(new ArrayList<>());
        store.save(sample("aaaa-0001", "à supprimer"));

        assertTrue(store.delete("aaaa-0001"));
        assertNull(store.load("aaaa-0001"));
        assertFalse(store.delete("aaaa-0001"), "le second effacement n'a rien trouvé");
    }

    @Test
    void freshConversationHasNoMessagesAndDerivesTitleLater() {
        Conversation conversation = Conversation.fresh("ab-cd", 10L);

        assertTrue(conversation.messages().isEmpty());
        assertEquals("", conversation.title());
        Conversation titled = conversation.withMessage(
                new Conversation.Message("user", "Première question"), 20L).withTitle(
                ConversationTitles.deriveTitle("Première question"), 20L);
        assertEquals("Première question", titled.title());
        assertEquals(20L, titled.updatedAt());
        assertEquals("user", titled.firstUserMessage().role());
        assertNull(Conversation.fresh("ef-gh", 1L).firstUserMessage());
    }

    private List<String> filesEndingBy(String suffix) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(temp.resolve("assistant"))) {
            stream.forEach(p -> {
                if (p.getFileName().toString().endsWith(suffix)) {
                    names.add(p.getFileName().toString());
                }
            });
        }
        return names;
    }
}
