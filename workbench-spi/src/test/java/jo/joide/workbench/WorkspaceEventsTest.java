package jo.joide.workbench;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lot 0 (tiroir gauche) : bus d'événements de l'espace de travail —
 * abonnement, publication à tous les abonnés, désabonnement sans fuite.
 */
class WorkspaceEventsTest {

    @Test
    void publishReachesEverySubscriberInOrder() {
        WorkspaceEvents bus = new WorkspaceEvents();
        List<String> seen = new ArrayList<>();
        bus.subscribe(event -> seen.add("a:" + event.getClass().getSimpleName()));
        bus.subscribe(event -> seen.add("b:" + event.getClass().getSimpleName()));

        bus.publish(new WorkspaceEvents.FileCreated(Paths.get("Main.java")));
        bus.publish(new WorkspaceEvents.GitStateChanged());

        assertEquals(List.of(
                "a:FileCreated", "b:FileCreated",
                "a:GitStateChanged", "b:GitStateChanged"), seen);
    }

    @Test
    void unsubscribeHandleStopsDeliveryWithoutLeak() {
        WorkspaceEvents bus = new WorkspaceEvents();
        List<String> seen = new ArrayList<>();
        Runnable unsubscribe = bus.subscribe(
                event -> seen.add(event.getClass().getSimpleName()));
        assertEquals(1, bus.subscriberCount());

        bus.publish(new WorkspaceEvents.BuildFinished(true));
        unsubscribe.run();
        bus.publish(new WorkspaceEvents.BuildFinished(false));

        assertEquals(1, seen.size());
        assertEquals(0, bus.subscriberCount(), "aucun abonné qui fuit");
    }

    @Test
    void payloadsCarryRealValues() {
        WorkspaceEvents bus = new WorkspaceEvents();
        List<WorkspaceEvents.Event> seen = new ArrayList<>();
        bus.subscribe(seen::add);

        bus.publish(new WorkspaceEvents.FileRenamed(
                Paths.get("a/Main.java"), Paths.get("b/Main.java")));
        bus.publish(new WorkspaceEvents.FileSaved(Paths.get("Main.java")));

        WorkspaceEvents.FileRenamed renamed =
                (WorkspaceEvents.FileRenamed) seen.get(0);
        assertEquals("a/Main.java", renamed.from().toString());
        assertEquals("b/Main.java", renamed.to().toString());
        assertEquals("Main.java", ((WorkspaceEvents.FileSaved) seen.get(1))
                .file().getFileName().toString());
    }
}
