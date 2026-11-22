package jo.joide.workbench;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Console de build (dock) : tâches mises à jour en place, progression
 * collante, bilan qui remplace la progression, capacité bornée honnête.
 */
class ConsoleBufferTest {

    @Test
    void appendDrainsOneMutationPerLine() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        console.header("> build Forge · 14:32");
        console.line(ConsoleBuffer.Kind.INFO, "préparation");

        List<ConsoleBuffer.Mutation> mutations = console.drain();

        assertEquals(2, mutations.size());
        assertEquals(ConsoleBuffer.Mutation.Type.APPEND, mutations.get(0).type);
        assertEquals(ConsoleBuffer.Kind.HEADER, mutations.get(0).entry.kind);
        assertEquals("> build Forge · 14:32", mutations.get(0).entry.text);
        assertTrue(console.drain().isEmpty());
    }

    @Test
    void startedTaskIsReplacedInPlaceByTerminalState() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        console.task(":app:compileJava", ConsoleBuffer.Kind.TASK_RUNNING, "◐ :app:compileJava");
        console.task(":app:compileJava", ConsoleBuffer.Kind.TASK_OK, "✔ :app:compileJava · 2,4 s");

        List<ConsoleBuffer.Mutation> mutations = console.drain();

        assertEquals(2, mutations.size());
        // La même ligne (même id) : APPEND puis UPDATE, jamais deux lignes.
        assertEquals(mutations.get(0).entry.id, mutations.get(1).entry.id);
        assertEquals(ConsoleBuffer.Mutation.Type.UPDATE, mutations.get(1).type);
        assertEquals(ConsoleBuffer.Kind.TASK_OK, mutations.get(1).entry.kind);
        assertEquals(1, console.size());
        assertEquals("✔ :app:compileJava · 2,4 s\n", console.plainText());
    }

    @Test
    void terminalWithoutStartedAppendsItsOwnLine() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        console.task(":core:compileJava", ConsoleBuffer.Kind.TASK_OK, "✔ :core:compileJava");

        assertEquals(1, console.size());
        assertEquals(ConsoleBuffer.Kind.TASK_OK, console.drain().get(0).entry.kind);
    }

    @Test
    void rerunOfTerminalTaskGetsAFreshLine() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        console.task(":t", ConsoleBuffer.Kind.TASK_RUNNING, "◐ :t");
        console.task(":t", ConsoleBuffer.Kind.TASK_OK, "✔ :t");
        console.task(":t", ConsoleBuffer.Kind.TASK_RUNNING, "◐ :t (2)");
        console.task(":t", ConsoleBuffer.Kind.TASK_FAILED, "✗ :t");

        // Deux lignes : le run 2 n'écrase pas l'historique du run 1.
        assertEquals(2, console.size());
        assertEquals("✔ :t\n✗ :t\n", console.plainText());
    }

    @Test
    void progressIsOneStickyLineNotSpam() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        for (int done = 1; done <= 5; done++) {
            console.progress("progression : " + done + "/5");
        }

        List<ConsoleBuffer.Mutation> mutations = console.drain();
        assertEquals(5, mutations.size());
        assertEquals(4, mutations.stream()
                .filter(m -> m.type == ConsoleBuffer.Mutation.Type.UPDATE).count());
        assertEquals(1, console.size());
        assertEquals("progression : 5/5\n", console.plainText());
    }

    @Test
    void finishedReplacesTheProgressLineWithTheResult() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        console.progress("progression : 59/59");
        console.finished(ConsoleBuffer.Kind.SUCCESS, "✔ build réussi · 18 s");

        assertEquals(1, console.size());
        assertEquals(ConsoleBuffer.Kind.SUCCESS, console.snapshot().get(0).kind);
        assertEquals("✔ build réussi · 18 s\n", console.plainText());
        // Après un bilan, une nouvelle progression est une nouvelle ligne
        // (en pratique : nouveau build, donc clear() est passé avant).
        console.progress("progression : 1/2");
        assertEquals(2, console.size());
        assertEquals("✔ build réussi · 18 s\nprogression : 1/2\n", console.plainText());
    }

    @Test
    void finishedWithoutProgressAppendsTheResult() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        console.finished(ConsoleBuffer.Kind.FAILURE, "✗ build échoué");

        assertEquals(1, console.size());
        assertEquals(ConsoleBuffer.Kind.FAILURE, console.snapshot().get(0).kind);
    }

    @Test
    void capacityTrimsOldestAndCountsHonestly() {
        ConsoleBuffer console = new ConsoleBuffer(10);
        for (int i = 1; i <= 15; i++) {
            console.line(ConsoleBuffer.Kind.INFO, "ligne " + i);
        }

        assertEquals(10, console.size());
        assertEquals(5, console.trimmedTotal());
        assertTrue(console.plainText().startsWith("ligne 6\n"));
        assertTrue(console.plainText().endsWith("ligne 15\n"));

        // 15 APPEND + un TRIM par ligne au-delà de la capacité.
        List<ConsoleBuffer.Mutation> mutations = console.drain();
        assertEquals(20, mutations.size());
        assertEquals(5, mutations.stream()
                .filter(m -> m.type == ConsoleBuffer.Mutation.Type.TRIM)
                .mapToInt(m -> m.count).sum());
        assertEquals(ConsoleBuffer.Mutation.Type.TRIM, mutations.get(11).type);
        assertEquals(1, mutations.get(11).count);
    }

    @Test
    void trimmedRunningTaskFallsBackToAppendOnTerminal() {
        ConsoleBuffer console = new ConsoleBuffer(12);
        console.task(":long", ConsoleBuffer.Kind.TASK_RUNNING, "◐ :long");
        for (int i = 0; i < 20; i++) {
            console.line(ConsoleBuffer.Kind.INFO, "bruit " + i);
        }
        // La ligne « en cours » a été élaguée : le terminal s'ajoute, sans
        // lever ni écraser une ligne disparue.
        console.task(":long", ConsoleBuffer.Kind.TASK_OK, "✔ :long");

        assertEquals(12, console.size());
        assertTrue(console.plainText().endsWith("✔ :long\n"));
    }

    @Test
    void clearResetsEverythingForANewBuild() {
        ConsoleBuffer console = new ConsoleBuffer(10);
        console.task(":t", ConsoleBuffer.Kind.TASK_RUNNING, "◐ :t");
        console.progress("progression : 1/2");
        console.line(ConsoleBuffer.Kind.INFO, "bruit");

        console.clear();

        assertEquals(0, console.size());
        assertEquals(0, console.trimmedTotal());
        assertEquals("", console.plainText());
        assertEquals(0, console.drain().size());
        console.finished(ConsoleBuffer.Kind.SUCCESS, "✔ ok");
        assertEquals(1, console.size());
    }

    @Test
    void snapshotIsACopyNotALiveView() {
        ConsoleBuffer console = new ConsoleBuffer(100);
        console.line(ConsoleBuffer.Kind.INFO, "avant");
        List<ConsoleBuffer.Entry> snapshot = console.snapshot();

        console.line(ConsoleBuffer.Kind.INFO, "après");
        console.task(":t", ConsoleBuffer.Kind.TASK_RUNNING, "◐ :t");

        assertEquals(1, snapshot.size());
        assertEquals("avant", snapshot.get(0).text);
        assertEquals(3, console.size());
    }

    @Test
    void minimumCapacityProtectsAgainstTinyValues() {
        ConsoleBuffer console = new ConsoleBuffer(2);
        for (int i = 0; i < 30; i++) {
            console.line(ConsoleBuffer.Kind.INFO, "l" + i);
        }
        // La capacité plancher (10) protège l'élagage des valeurs absurdes.
        assertEquals(10, console.size());
        assertTrue(console.plainText().endsWith("l29\n"));
    }
}
