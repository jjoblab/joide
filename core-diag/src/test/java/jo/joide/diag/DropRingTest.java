package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DropRingTest {

    @Test
    void evicteLePlusAncienEtCompteLesPertes() throws Exception {
        DropRing<Integer> ring = new DropRing<>(3);
        for (int i = 1; i <= 5; i++) {
            ring.offer(i);
        }
        assertEquals(3, ring.size());
        assertEquals(2L, ring.dropped(), "deux éléments évictés, comptés");
        assertEquals(3, ring.take());
        assertEquals(4, ring.take());
        assertEquals(5, ring.take());
    }

    @Test
    void pollRendNullAuDelaDuDelai() throws Exception {
        DropRing<Integer> ring = new DropRing<>(2);
        long start = System.nanoTime();
        assertNullQuiet(ring, 50L);
        assertTrue((System.nanoTime() - start) / 1_000_000L >= 40L,
                "l'attente a durée au moins le délai demandé");
    }

    @Test
    void pollAvecDelaiNulNeBloquePas() throws Exception {
        DropRing<Integer> ring = new DropRing<>(2);
        assertEquals(null, ring.poll(0L));
    }

    @Test
    void debloqueLEcritureBloqueeEnLecture() throws Exception {
        DropRing<Integer> ring = new DropRing<>(4);
        Thread writer = new Thread(() -> {
            try {
                ring.take();
                ring.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        Thread.sleep(50L);
        ring.offer(1);
        ring.offer(2);
        writer.join(2_000L);
        assertTrue(!writer.isAlive(), "les offres ont réveillé le lecteur bloqué");
    }

    private static void assertNullQuiet(DropRing<Integer> ring, long timeout) throws Exception {
        Integer value = ring.poll(timeout);
        assertTrue(value == null);
    }
}
