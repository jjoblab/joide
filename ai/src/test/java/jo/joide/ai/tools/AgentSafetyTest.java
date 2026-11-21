package jo.joide.ai.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import jo.joide.ai.AgentLoop;
import jo.joide.ai.AgentWorkspace;
import jo.joide.ai.DiffBuilder;
import jo.joide.ai.LlmTransport;
import jo.joide.ai.PermissionGate;
import jo.joide.ai.ProjectInstructions;
import jo.joide.ai.SearchOptions;
import jo.joide.ai.TurnCheckpoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 3 : agent plus intelligent et plus sûr — arguments typés, espace de
 * travail durci (évasion, chemins protégés, binaire, taille), remplacement
 * ciblé 0/1/n, diff réel, point de contrôle avec aveu des suppressions,
 * détection de boucle, instructions projet bornées et cachées.
 */
class AgentSafetyTest {

    @TempDir
    Path temp;

    private AgentWorkspace.OnProject project() throws IOException {
        Path root = temp.resolve("projet");
        Files.createDirectories(root);
        return new AgentWorkspace.OnProject(root);
    }

    private Path root() throws IOException {
        Path root = temp.resolve("projet");
        Files.createDirectories(root);
        return root;
    }

    // ---- ToolArgs ----------------------------------------------------------

    @Test
    void toolArgsAcceptEscapedQuotesNewlinesAndUnicode() throws Exception {
        ToolArgs args = ToolArgs.of("{\"chemin\":\"Main.java\",\"contenu\":"
                + "\"a = \\\"b{c}\\\";\\nligne é✦\"}");
        assertEquals("Main.java", args.string("chemin"));
        assertEquals("a = \"b{c}\";\nligne é✦", args.string("contenu"));
    }

    @Test
    void missingArgumentBecomesAModelFacingError() {
        ToolArgs.BadArguments bad = assertThrows(ToolArgs.BadArguments.class,
                () -> ToolArgs.of("{\"autre\":1}").string("chemin"));
        assertTrue(bad.getMessage().contains("chemin"), "le message nomme l'argument");
    }

    @Test
    void invalidJsonBecomesAModelFacingError() {
        assertThrows(ToolArgs.BadArguments.class, () -> ToolArgs.of("{\"chemin\":"));
    }

    // ---- Sécurité de l'espace de travail -----------------------------------

    @Test
    void pathEscapeIsRefusedEverywhere() throws Exception {
        AgentWorkspace.OnProject ws = project();
        for (String escape : new String[] {"../hors.txt", "a/../../hors.txt",
                "/absolu.txt"}) {
            assertThrows(IOException.class, () -> ws.writeFile(escape, "x"),
                    "refusé : " + escape);
        }
        // Un chemin légitime passe toujours (retour = caractères écrits).
        assertEquals(2, ws.writeFile("src/Main.java", "ok"));
    }

    @Test
    void protectedPathsAreNeverWritten() throws Exception {
        AgentWorkspace.OnProject ws = project();
        for (String path : new String[] {".git/config", "local.properties", ".env",
                "release.jks", "certificat.keystore", "cle.pem", "a/b/.env"}) {
            assertThrows(IOException.class, () -> ws.writeFile(path, "x"),
                    "protégé : " + path);
        }
        // .gitignore reste éditable (texte de configuration normal).
        ws.writeFile(".gitignore", "build/\n");
        assertTrue(ws.fileBefore(".gitignore").contains("build"));
    }

    @Test
    void binaryFilesAreRefusedOnRead() throws Exception {
        Path root = root();
        Files.write(root.resolve("binaire.bin"),
                new byte[] {1, 2, 0, 3, 4}); // octet nul = binaire
        AgentWorkspace.OnProject ws = new AgentWorkspace.OnProject(root);
        assertThrows(IOException.class, () -> ws.readText("binaire.bin"));
    }

    @Test
    void oversizedFilesAreRefusedOnRead() throws Exception {
        Path root = root();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600 * 1024; i++) {
            big.append('x');
        }
        Files.write(root.resolve("gros.txt"),
                big.toString().getBytes(StandardCharsets.UTF_8));
        AgentWorkspace.OnProject ws = new AgentWorkspace.OnProject(root);
        assertThrows(IOException.class, () -> ws.readText("gros.txt"));
    }

    @Test
    void generatedDirsAreNeverListedInTheTree() throws Exception {
        Path root = root();
        Files.createDirectories(root.resolve("app/src/main/java"));
        Files.createDirectories(root.resolve("app/build/generated"));
        Files.write(root.resolve("app/src/main/java/Main.java"),
                "class Main {}".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("app/build/generated/Poubelle.java"),
                "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve(".git"));
        Files.write(root.resolve(".git/config"), "x".getBytes(StandardCharsets.UTF_8));
        AgentWorkspace.OnProject ws = new AgentWorkspace.OnProject(root);

        String tree = ws.listTree(".", 8, 200).content();

        assertTrue(tree.contains("Main.java"));
        assertFalse(tree.contains("Poubelle"), "build/ ignoré");
        assertFalse(tree.contains("config"), ".git/ jamais listé");
        assertFalse(tree.contains("build/"), "pas de dossier généré");
    }

    // ---- applyEdit : 0 / 1 / n occurrences ---------------------------------

    @Test
    void applyEditFailsHonestlyOnZeroOneOrManyOccurrences() throws Exception {
        AgentWorkspace.InMemory ws = new AgentWorkspace.InMemory();
        ws.put("Main.java", "int a = 1;\nint b = 2;\n");

        // 0 occurrence : introuvable, message utile.
        IOException zero = assertThrows(IOException.class,
                () -> ws.applyEdit("Main.java", "introuvable", "x"));
        assertTrue(zero.getMessage().contains("introuvable"));

        // n occurrences : ambigu, demande de contexte.
        ws.put("Doublons.java", "x = 1;\nx = 1;\n");
        IOException many = assertThrows(IOException.class,
                () -> ws.applyEdit("Doublons.java", "x = 1;", "x = 2;"));
        assertTrue(many.getMessage().contains("ambigu"));

        // 1 occurrence : remplacement exact.
        ws.applyEdit("Main.java", "int a = 1;", "int a = 10;");
        assertEquals("int a = 10;\nint b = 2;\n", ws.get("Main.java"));
    }

    @Test
    void modifyFileToolAppliesTargetedReplacement() throws Exception {
        AgentTools.ModifyFileTool tool = new AgentTools.ModifyFileTool();
        AgentWorkspace.InMemory ws = new AgentWorkspace.InMemory();
        ws.put("Main.java", "greet(name);\n");
        String result = tool.run(ws, ToolArgs.of("{\"chemin\":\"Main.java\","
                + "\"ancien_texte\":\"greet(name);\",\"nouveau_texte\":"
                + "\"Objects.requireNonNull(name);\\ngreet(name);\"}"));
        assertTrue(result.contains("Modifié"));
        assertTrue(ws.get("Main.java").contains("requireNonNull"));
        assertFalse(tool.readOnly());
    }

    @Test
    void argumentsWithEscapedContentSurviveTheWholeToolPath() throws Exception {
        AgentTools.CreateFileTool tool = new AgentTools.CreateFileTool();
        AgentWorkspace.InMemory ws = new AgentWorkspace.InMemory();
        tool.run(ws, ToolArgs.of("{\"chemin\":\"A.java\",\"contenu\":"
                + "\"s = \\\"guillemet \\\\\\\" échappé\\\";\\n// é✦\"}"));
        assertTrue(ws.get("A.java").contains("\"guillemet \\\" échappé\""));
        assertTrue(ws.get("A.java").contains("é✦"));
    }

    // ---- Vraie recherche ----------------------------------------------------

    @Test
    void searchFindsRealOccurrencesWithOptions() throws Exception {
        AgentWorkspace.InMemory ws = new AgentWorkspace.InMemory();
        ws.put("Main.java", "int nombre = 1;\nint nombres = 2;\n");
        ws.put("Layout.xml", "<string name=\"nombre\">valeur</string>\n");

        // Mot entier : « nombre » ne matche pas « nombres ».
        String whole = ws.search("nombre",
                new SearchOptions(false, false, true, "*.java", 0), 30).content();
        assertTrue(whole.contains("Main.java:1"));
        assertFalse(whole.contains("nombres"));

        // Filtre de fichiers : seul le XML correspond sans filtre ; avec
        // *.java le XML est exclu.
        String all = ws.search("nombre", SearchOptions.text(), 30).content();
        assertTrue(all.contains("Layout.xml"), "sans filtre : tout le projet");
        String filtered = ws.search("nombre",
                new SearchOptions(false, false, false, "*.java", 0), 30).content();
        assertFalse(filtered.contains("Layout.xml"), "avec *.java : XML exclu");
    }

    // ---- DiffBuilder ----------------------------------------------------------

    @Test
    void diffBuilderCountsAndFormatsExactly() {
        DiffBuilder.Diff diff = DiffBuilder.unified(
                "ligne1\nligne2\nligne3\n", "ligne1\nligne MODIFIEE\nligne3\nligne4\n");
        assertEquals(2, diff.added());
        assertEquals(1, diff.removed());
        assertEquals("+2 −1", diff.counters());
        assertTrue(diff.unified().contains("@@"));
        assertTrue(diff.unified().contains("-ligne2"));
        assertTrue(diff.unified().contains("+ligne MODIFIEE"));
        assertTrue(diff.unified().contains("+ligne4"));

        DiffBuilder.Diff identical = DiffBuilder.unified("pareil\n", "pareil\n");
        assertEquals(0, identical.added());
        assertEquals(0, identical.removed());
    }

    @Test
    void diffBuilderRefusesOversizedFiles() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= DiffBuilder.MAX_LINES + 1; i++) {
            big.append("ligne\n");
        }
        assertNull(DiffBuilder.unified(big.toString(), big.toString() + "encore\n"),
                "fichier trop grand : le diff se refuse (dit honnêtement)");
    }

    // ---- TurnCheckpoint ---------------------------------------------------------

    @Test
    void checkpointRestoresModificationsAndAdmitsDeletions() throws Exception {
        AgentWorkspace.InMemory ws = new AgentWorkspace.InMemory();
        ws.put("Main.java", "avant");
        ws.put("A-supprimer.txt", "contenu");
        TurnCheckpoint checkpoint = new TurnCheckpoint(ws);

        checkpoint.beforeChange("Main.java");
        ws.writeFile("Main.java", "après");
        checkpoint.afterChange("Main.java", false);

        checkpoint.beforeChange("A-supprimer.txt");
        ws.deleteFile("A-supprimer.txt");
        checkpoint.afterChange("A-supprimer.txt", true);

        String note = checkpoint.undo();

        assertTrue(ws.get("Main.java").equals("avant"), "modification restaurée");
        assertTrue(note.contains("restauré"), "la note dit ce qui est restauré");
        assertTrue(note.contains("irréversible"), "la note avoue la suppression");
        assertTrue(note.contains("A-supprimer.txt"), "la note nomme le fichier perdu");
    }

    @Test
    void checkpointRemovesFilesCreatedDuringTheTurn() {
        AgentWorkspace.InMemory ws = new AgentWorkspace.InMemory();
        TurnCheckpoint checkpoint = new TurnCheckpoint(ws);
        checkpoint.beforeChange("Nouveau.java"); // absent avant → création
        ws.createFile("Nouveau.java", "tout neuf");
        checkpoint.afterChange("Nouveau.java", false);

        checkpoint.undo();

        assertNull(ws.get("Nouveau.java"), "la création disparaît à l'annulation");
    }

    // ---- Détection de boucle -----------------------------------------------------

    /** Transport mock minimal pour la boucle. */
    private static final class LoopMock implements LlmTransport {
        private int calls;
        private final String arguments;
        private LoopMock(String arguments) {
            this.arguments = arguments;
        }
        @Override public String fetchModelsJson(Endpoint endpoint) {
            return "{\"data\":[]}";
        }
        @Override public void chat(Endpoint endpoint, ChatRequest request,
                java.util.function.Consumer<StreamEvent> onEvent) {
            calls++;
            if (calls <= 4) {
                onEvent.accept(new StreamEvent.ToolCallEvent(new ToolCall(
                        "c" + calls, "lire_fichier", arguments)));
            } else {
                onEvent.accept(new StreamEvent.Text("bilan"));
            }
            onEvent.accept(new StreamEvent.Finish("stop"));
        }
    }

    @Test
    void repeatedIdenticalCallsStopTheTurnWithAWrapUp() {
        LoopMock transport = new LoopMock("{\"chemin\":\"Main.java\"}");
        AgentLoop loop = new AgentLoop(transport,
                new LlmTransport.Endpoint("http://l/chat", "http://l/models", null),
                "m", new AgentWorkspace.InMemory(),
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT), 10);
        List<AgentLoop.LoopEvent> events = new CopyOnWriteArrayList<>();

        loop.turn("fais tourner en boucle", events::add);

        // 3 appels identiques → arrêt ; le tour se termine par une réponse de
        // clôture (pas un échec).
        assertTrue(events.get(events.size() - 1)
                instanceof AgentLoop.LoopEvent.TurnCompleted,
                "arrêt en bilan, pas en échec");
        assertTrue(transport.calls <= 5, "le modèle ne tourne plus en rond");
    }

    @Test
    void loopExposesCheckpointOfTheLastTurn() {
        LoopMock transport = new LoopMock("{\"chemin\":\"Main.java\"}");
        AgentLoop loop = new AgentLoop(transport,
                new LlmTransport.Endpoint("http://l/chat", "http://l/models", null),
                "m", new AgentWorkspace.InMemory(),
                new PermissionGate.Scripted(PermissionGate.Mode.AUTO_ACCEPT), 10);

        loop.turn("un tour", e -> { });

        assertNotNull(loop.checkpoint(), "l'hôte peut annuler le tour");
    }

    // ---- Instructions projet --------------------------------------------------------

    @Test
    void projectInstructionsPreferAgentsMdThenClaudeMdBounded() throws Exception {
        Path root = root();
        Files.write(root.resolve("AGENTS.md"),
                "règles du projet".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("CLAUDE.md"),
                "second choix".getBytes(StandardCharsets.UTF_8));
        assertEquals("règles du projet", ProjectInstructions.cached(root));

        Files.delete(root.resolve("AGENTS.md"));
        // Le cache suit le mtime : le second fichier est lu au rechargement.
        assertEquals("second choix", ProjectInstructions.cached(root));

        // Borné : au-delà de 16 Ko, les instructions sont tronquées et dites.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20 * 1024; i++) {
            big.append('x');
        }
        Files.write(root.resolve("AGENTS.md"),
                big.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(ProjectInstructions.cached(root).contains("tronquées"));

        assertEquals("", ProjectInstructions.cached(root.resolve("sans-fichier")));
    }
}
