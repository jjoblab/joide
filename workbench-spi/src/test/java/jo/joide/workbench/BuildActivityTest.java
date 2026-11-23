package jo.joide.workbench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Banc du sémaphore d'activité de build (L10) : bascule, réveil à la fin du build,
 * attente bornée (timeout — jamais de blocage), auditeurs.
 */
class BuildActivityTest {

    @Test
    void basculeEtRéveilÀLaFinDuBuild() throws Exception {
        BuildActivity activity = new BuildActivity();
        assertFalse(activity.isCompiling());
        activity.set(true);
        assertTrue(activity.isCompiling());

        Thread worker = new Thread(() -> {
            try {
                // Le travail de fond attend la fin du build (borné à 5 s au test).
                assertTrue(activity.awaitIdle(5000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        Thread.sleep(100); // le worker est bloqué dans l'attente
        assertTrue(worker.isAlive());
        activity.set(false); // fin du build → réveil
        worker.join(2000);
        assertFalse(worker.isAlive());
    }

    @Test
    void attenteBornéeRendLaMain() throws Exception {
        BuildActivity activity = new BuildActivity();
        activity.set(true);
        long start = System.currentTimeMillis();
        assertFalse(activity.awaitIdle(150)); // timeout : rend faux, pas de blocage
        assertTrue(System.currentTimeMillis() - start >= 140);
        activity.set(false);
        assertTrue(activity.awaitIdle(10)); // maintenant calme
    }

    @Test
    void auditeursAppelésEtDésabonnementPropre() {
        BuildActivity activity = new BuildActivity();
        AtomicInteger changes = new AtomicInteger();
        Runnable unsubscribe = activity.listen(compiling -> changes.incrementAndGet());
        activity.set(true);
        activity.set(true);
        activity.set(false);
        unsubscribe.run();
        activity.set(true);
        assertEquals(3, changes.get()); // le désabonnement est immédiat
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
