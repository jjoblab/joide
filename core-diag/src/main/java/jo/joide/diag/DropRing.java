package jo.joide.diag;

/**
 * File bornée à éviction du plus ancien (DROP_OLDEST) pour le sink fichier
 * (ADR-021) : les producteurs ne bloquent <b>jamais</b> ; sous stress, la
 * perte est assumée et <b>comptée</b> ({@link #dropped()}) — remontée dans le
 * diagnostic, jamais silencieuse.
 *
 * <p>Un seul consommateur attendu (le fil du sink) ; plusieurs producteurs.
 * Sections critiques minuscules : offre et retrait, rien d'autre.</p>
 */
final class DropRing<T> {

    private final Object[] slots;

    /** Indice du plus ancien élément. */
    private int head;

    /** Nombre d'éléments présents. */
    private int size;

    /** Éléments perdus par éviction depuis le démarrage. */
    private long dropped;

    DropRing(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("La capacité de la file doit être positive.");
        }
        this.slots = new Object[capacity];
    }

    /**
     * Offre un élément — retour immédiat ; si la file est pleine, le plus
     * ancien élément part (perte comptée).
     */
    synchronized void offer(T item) {
        if (item == null) {
            return;
        }
        if (size == slots.length) {
            slots[head] = null;
            head = (head + 1) % slots.length;
            size--;
            dropped++;
        }
        slots[(head + size) % slots.length] = item;
        size++;
        notifyAll();
    }

    /**
     * Attend et retire un élément — bloque tant que la file est vide.
     *
     * @throws InterruptedException fil interrompu (arrêt du sink)
     */
    synchronized T take() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        return removeOldest();
    }

    /**
     * Retire un élément dans la limite du délai.
     *
     * @param timeoutMillis attente maximale (0 ou moins : sondage immédiat)
     * @return l'élément, ou {@code null} au délai écoulé
     * @throws InterruptedException fil interrompu (arrêt du sink)
     */
    synchronized T poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0) {
            return size == 0 ? null : removeOldest();
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (size == 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        }
        return removeOldest();
    }

    /** Éléments perdus par éviction depuis le démarrage. */
    synchronized long dropped() {
        return dropped;
    }

    /** Éléments actuellement présents. */
    synchronized int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private T removeOldest() {
        T item = (T) slots[head];
        slots[head] = null;
        head = (head + 1) % slots.length;
        size--;
        return item;
    }
}
