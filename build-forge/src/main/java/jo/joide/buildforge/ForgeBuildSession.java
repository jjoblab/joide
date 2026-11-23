package jo.joide.buildforge;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Consumer;
import jo.forge.api.BuildRequest;
import jo.forge.client.EngineClient;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.BuildSession;

/**
 * Session d'un build Forge : adapte {@link EngineClient} au contrat
 * {@link BuildSession} de JoIDE. Les événements du moteur sont traduits
 * ({@link ForgeEventTranslator}) puis diffusés à chaque abonné ; la fin de build
 * ({@code Finished}) reste le dernier événement, quelle que soit la façon dont
 * le build s'arrête (succès, échec, annulation, moteur mort).
 *
 * <p>File d'attente bornée côté abonné : le journal du moteur peut être bavard
 * pendant un build ; l'atelier consomme à son rythme (conception §4.6 : ne jamais
 * bloquer le moteur sur l'affichage).</p>
 */
final class ForgeBuildSession implements BuildSession {

    private final String id;
    private final EngineClient engine;
    private final SubmissionPublisher<BuildEvent> events;
    private final ConcurrentLinkedQueue<BuildEvent> backlog;
    private volatile boolean finished;

    ForgeBuildSession(String id, EngineClient engine) {
        this.id = Objects.requireNonNull(id);
        this.engine = Objects.requireNonNull(engine);
        // Tampon borné (16) : l'affichage lent ne bloque jamais le moteur.
        this.events = new SubmissionPublisher<>(Runnable::run, 16);
        this.backlog = new ConcurrentLinkedQueue<>();
        this.finished = false;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void subscribe(Consumer<BuildEvent> consumer) {
        Flow.Subscriber<BuildEvent> subscriber = new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(BuildEvent item) { consumer.accept(item); }
            @Override public void onError(Throwable t) { /* le moteur ne meurt pas ici */ }
            @Override public void onComplete() { consumer.accept(new BuildEvent.Finished(
                    false, null, 0, "Flux du moteur terminé sans fin de build (mort anormale)")); }
        };
        // Rejeu du backlog (événements arrivés avant l'abonnement) puis diffusion.
        backlog.forEach(consumer);
        events.subscribe(subscriber);
    }

    /** Soumet un événement traduit (backlog + diffusion). */
    void emit(BuildEvent event) {
        backlog.add(event);
        events.submit(event);
        if (event instanceof BuildEvent.Finished) {
            finished = true;
            events.close();
        }
    }

    @Override
    public void cancel() {
        // L'accusé Cancelled ne compte pas comme fin de build : le moteur émet
        // ensuite sa BuildFinished propre (Forge garantit un dernier événement).
        engine.cancel(id);
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    /**
     * Forge v0.2.26 (protocole 4) : le moteur compose et TRIE lui-même la
     * console (décision D-21) — la coquille affiche. Vrai dès que le moteur
     * négocié parle le protocole 4 ; faux face à un moteur plus ancien (la
     * composition locale d'avant reste la règle, rien ne double).
     */
    @Override
    public boolean engineConsole() {
        return engine.protocolVersion() >= 4;
    }

    /** À la mort du moteur : clôt la session pour ne pas laisser l'atelier suspendu. */
    void onEngineDead() {
        if (!finished) {
            emit(new BuildEvent.Finished(false, null, 0, "Le moteur Forge s'est arrêté (journal : "
                    + String.join("\\n", engine.journalErreurs().stream()
                        .limit(5).toList()) + ")"));
        }
    }

    /** Requête de build Forge pour ce projet (racine = dossier contenant forge.json).
     *
     * <p>Le niveau de console est un ARGUMENT du moteur (protocole 4, équivalent
     * des {@code -q/-i/-d} de Gradle) : le moteur n'émet sur le canal que les
     * entrées dont la sévérité atteint ce niveau — le tri de référence se fait
     * côté moteur, la coquille demande.</p>
     */
    static BuildRequest forgeRequest(String buildId, String projectRoot, boolean clean,
            jo.joide.workbench.BuildEvent.Log.Level consoleLevel,
            ForgeEngineManager.DeviceProfile device) {
        // Options fixées par la coquille (Forge) : parallélisme 2 comme la coquille
        // de Forge, mode basse mémoire sous 2,5 Go, ABI réelle pour les composants
        // natifs — jamais devinés par le moteur.
        boolean lowMemory = device.totalRamMb() > 0
                && device.totalRamMb() < ForgeEngineManager.LOW_MEMORY_THRESHOLD_MB;
        return new BuildRequest(buildId, projectRoot,
                new jo.forge.api.BuildOptions(2, lowMemory, false, false,
                        device.totalRamMb(), device.abi(), niveauForge(consoleLevel)));
    }

    /** Le niveau de console de l'atelier, traduit en niveau du moteur. */
    private static jo.forge.api.BuildEvent.LogEvent.LogLevel niveauForge(
            jo.joide.workbench.BuildEvent.Log.Level niveau) {
        return switch (niveau == null
                ? jo.joide.workbench.BuildRequest.NIVEAU_PAR_DÉFAUT : niveau) {
            case DEBUG -> jo.forge.api.BuildEvent.LogEvent.LogLevel.DEBUG;
            case INFO -> jo.forge.api.BuildEvent.LogEvent.LogLevel.INFO;
            case LIFECYCLE -> jo.forge.api.BuildEvent.LogEvent.LogLevel.LIFECYCLE;
            case WARNING -> jo.forge.api.BuildEvent.LogEvent.LogLevel.WARN;
            case ERROR -> jo.forge.api.BuildEvent.LogEvent.LogLevel.ERROR;
        };
    }
}
