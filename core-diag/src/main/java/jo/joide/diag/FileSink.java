package jo.joide.diag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sink fichier <b>asynchrone</b> (portage CodeIDE §5.7, ADR-021) : le
 * producteur ne fait qu'offrir l'entrée à une file bornée (DROP_OLDEST —
 * l'appelant n'est <b>jamais bloqué</b>, la perte sous stress est assumée et
 * comptée par {@link #droppedEntries()}).
 *
 * <p>Le consommateur unique (fil démon « joide-diag-file ») regroupe les
 * écritures : au plus une écriture toutes les
 * {@link LoggingLimits#BATCH_WINDOW_MS} millisecondes, sauf entrée
 * {@code ERROR} qui déclenche l'écriture immédiate, et sauf ordre de vidage.
 * Le démarrage balaie d'abord la rétention des archives.</p>
 *
 * <p>{@link #flushBlocking(long)} est le point d'entrée <b>bloquant et
 * borné</b> réservé au gestionnaire de plantages et aux tests : il envoie un
 * ordre de vidage dans la file (donc <b>après</b> tout ce qui précède) et
 * attend l'accusé du consommateur — jamais d'attente sans limite.</p>
 *
 * <p>Améliorations par rapport au portage d'origine (coroutines) : 100 %
 * Java sans dépendance, compteur d'évictions explicite et compteur d'échecs
 * d'écriture consultables par le diagnostic, arrêt propre ({@link #stop()}).</p>
 */
public final class FileSink implements LogSink {

    /** Ordre de vidage immédiat, accusé par un verrou de coordination. */
    private record Flush(CountDownLatch latch) {}

    private final JsonlLogStore store;
    private final LongSupplier clock;
    private final Supplier<LogConfig> configSupplier;
    private final Consumer<IOException> failureListener;
    private final DropRing<Object> queue = new DropRing<>(LoggingLimits.CHANNEL_CAPACITY);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong writeErrorCount = new AtomicLong();

    private volatile Thread worker;
    private volatile boolean stopped;

    /**
     * @param store           stockage JSONL propriétaire du disque
     * @param clock           horloge des fenêtres de groupement (tests)
     * @param configSupplier  configuration lue à chaque écriture (à chaud)
     * @param failureListener notifié de chaque échec d'écriture disque — en
     *                        production : un WARN vers logcat
     */
    public FileSink(JsonlLogStore store, LongSupplier clock,
            Supplier<LogConfig> configSupplier, Consumer<IOException> failureListener) {
        this.store = store;
        this.clock = clock;
        this.configSupplier = configSupplier;
        this.failureListener = failureListener == null ? failure -> { } : failureListener;
    }

    @Override
    public LogLevel minLevel() {
        return LogLevel.DEBUG;
    }

    /**
     * Offre une entrée à l'écriture asynchrone — retour immédiat, perte
     * acceptée si la file est saturée (capacité
     * {@link LoggingLimits#CHANNEL_CAPACITY}, politique DROP_OLDEST).
     *
     * <p>Obéit aussi à l'interdiction d'écrire (fileLoggingEnabled à false) :
     * rien n'est offert.</p>
     */
    @Override
    public void write(LogEntry entry) {
        if (!configSupplier.get().fileLoggingEnabled()) {
            return;
        }
        queue.offer(entry);
    }

    /**
     * Démarre le consommateur unique : rétention initiale, puis boucle de
     * groupement. Idempotent.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::run, "joide-diag-file");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Arrête le consommateur (interruption + dernier lot) — pour les tests ;
     * en production le fil est démon et vit avec le processus.
     */
    public void stop() {
        stopped = true;
        Thread worker = this.worker;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Vide la file en attente, dans la limite du délai.
     *
     * <p>Primitive de secours du gestionnaire de plantages : jamais appelée
     * depuis le fil principal en production.</p>
     *
     * @param timeoutMillis durée maximale d'attente
     * @return {@code true} si le consommateur a accusé le vidage dans le délai
     */
    public boolean flushBlocking(long timeoutMillis) {
        if (!started.get()) {
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        queue.offer(new Flush(latch));
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Échecs d'écriture disque depuis le démarrage (sonde de diagnostic). */
    public long writeErrorCount() {
        return writeErrorCount.get();
    }

    /** Entrées perdues par éviction de la file depuis le démarrage. */
    public long droppedEntries() {
        return queue.dropped();
    }

    // ---- Boucle du consommateur unique -----------------------------------------

    private void run() {
        LogConfig config = configSupplier.get();
        try {
            store.sweep(clock.getAsLong(), config.retentionDays(), config.maxArchiveFiles());
        } catch (Throwable sweepFailure) {
            // Le balayage ne doit jamais empêcher l'écriture de démarrer.
        }
        List<LogEntry> pending = new ArrayList<>();
        long deadline = 0L;
        while (!stopped) {
            Object event;
            try {
                if (pending.isEmpty()) {
                    event = queue.take();
                } else {
                    long remaining = deadline - clock.getAsLong();
                    long window = Math.max(0L, Math.min(remaining, LoggingLimits.BATCH_WINDOW_MS));
                    event = queue.poll(window);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) {
                // Fenêtre écoulée : le groupe part.
                writeBatch(pending);
                pending.clear();
            } else if (event instanceof Flush flush) {
                writeBatch(pending);
                pending.clear();
                flush.latch().countDown();
            } else if (event instanceof LogEntry entry) {
                if (pending.isEmpty()) {
                    deadline = clock.getAsLong() + LoggingLimits.BATCH_WINDOW_MS;
                }
                pending.add(entry);
                if (entry.level() == LogLevel.ERROR) {
                    // Une erreur part tout de suite — jamais différée.
                    writeBatch(pending);
                    pending.clear();
                }
            }
        }
        writeBatch(pending);
    }

    /**
     * Écrit un groupe d'entrées via le stockage ; l'échec d'I/O est compté et
     * notifié — jamais propagé au consommateur.
     */
    private void writeBatch(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>(entries.size());
            for (LogEntry entry : entries) {
                lines.add(LogEntryJson.write(entry));
            }
            LogConfig config = configSupplier.get();
            store.appendLines(lines, config.maxFileBytes(), config.maxArchiveFiles());
        } catch (IOException failure) {
            writeErrorCount.incrementAndGet();
            try {
                failureListener.accept(failure);
            } catch (Throwable listenerBroken) {
                // Le listener ne doit jamais casser le consommateur.
            }
        }
    }
}
