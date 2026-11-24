package jo.joide.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tampon circulaire des {@link LoggingLimits#BREADCRUMB_CAPACITY} dernières
 * entrées de journal (<i>filons</i>), en mémoire (portage CodeIDE, ADR-021).
 *
 * <p>Source du flux « entrées récentes » et des 50 derniers indices de
 * contexte joints aux rapports de plantage. Thread-safe par sections
 * critiques minuscules (aucune I/O, aucun calcul) : lisible depuis n'importe
 * quel fil sans bloquer les producteurs.</p>
 */
final class CircularLogBuffer {

    private final int capacity;
    private final Deque<LogEntry> deque = new ArrayDeque<>();

    CircularLogBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("La capacité du tampon circulaire doit être positive.");
        }
        this.capacity = capacity;
    }

    /**
     * Ajoute une entrée, en éviction de la plus ancienne si le tampon est plein.
     *
     * @param entry entrée à conserver
     */
    void add(LogEntry entry) {
        synchronized (deque) {
            if (deque.size() == capacity) {
                deque.removeFirst();
            }
            deque.addLast(entry);
        }
    }

    /**
     * Instantané des entrées conservées, de la plus ancienne à la plus
     * récente — copie indépendante, sûre à parcourir hors verrou.
     */
    List<LogEntry> snapshot() {
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /** Efface le tampon. */
    void clear() {
        synchronized (deque) {
            deque.clear();
        }
    }

    /** Nombre courant d'entrées conservées. */
    int size() {
        synchronized (deque) {
            return deque.size();
        }
    }
}
