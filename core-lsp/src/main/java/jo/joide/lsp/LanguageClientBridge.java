package jo.joide.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Le client LSP de JoIDE (B-02 : « JoIDE câblera son propre client lsp4j
 * directement, aucun des deux n'impose l'autre ») : l'extrémité cliente
 * branchée en processus aux serveurs de la brique code-lsp.
 *
 * <p>Le contrat est volontairement MINCE : tout ce que les moteurs
 * publient et que l'atelier consomme réellement passe par des auditeurs —
 * les diagnostics ({@code publishDiagnostics}, debounce 50 ms côté serveur)
 * et le journal ({@code logMessage}, verbosité des moteurs). Le reste des
 * méthodes de l'interface répond le vide honnête du protocole : un client
 * muet n'interrompt jamais un serveur.</p>
 *
 * <p>Le branchement est DIRECT (aucun tuyau JSON-RPC) : le
 * {@link LanguageServer} rendu par l'hôte LSP est un objet du processus —
 * {@code server.connect(bridge)} puis {@code server.initialize(...)} et les
 * appels partent en Java franc. La session d'édition
 * (app-android) enveloppe chaque requête sur un fil dédié : le moteur ecj
 * ne compile jamais sur le fil de l'interface.</p>
 */
public final class LanguageClientBridge implements LanguageClient {

    /** Un diagnostic publié : (uri, diagnostics lsp4j) — fil du moteur. */
    private final List<BiConsumer<String, List<org.eclipse.lsp4j.Diagnostic>>>
            auditeursDiagnostics = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Un message de journal publié : (type, message) — fil du moteur. */
    private final List<BiConsumer<String, String>> auditeursJournal =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Ajoute un auditeur de diagnostics (retire par la poignée rendue). */
    public Runnable onDiagnostics(
            BiConsumer<String, List<org.eclipse.lsp4j.Diagnostic>> auditeur) {
        auditeursDiagnostics.add(auditeur);
        return () -> auditeursDiagnostics.remove(auditeur);
    }

    /** Ajoute un auditeur du journal des moteurs (retire par la poignée). */
    public Runnable onLog(BiConsumer<String, String> auditeur) {
        auditeursJournal.add(auditeur);
        return () -> auditeursJournal.remove(auditeur);
    }

    /**
     * Branche un client à un serveur LSP en suivant les TROIS conventions de
     * la brique code-lsp : les serveurs Java et Kotlin exposent leur propre
     * {@code connect(LanguageClient)}, le serveur XML suit la convention
     * {@code setClient(LanguageClient)} (cel-lsp l'appelle par réflexion),
     * et la bibliothèque standard pose {@code LanguageClientAware} en dernier
     * recours. Aucune convention trouvée : le serveur vit sans rappels — le
     * branchement ne plante jamais l'hôte.
     */
    public static void brancher(org.eclipse.lsp4j.services.LanguageServer serveur,
            org.eclipse.lsp4j.services.LanguageClient client) {
        for (String convention : new String[] {"connect", "setClient"}) {
            try {
                java.lang.reflect.Method poseur = serveur.getClass()
                        .getMethod(convention,
                                org.eclipse.lsp4j.services.LanguageClient.class);
                poseur.invoke(serveur, client);
                return;
            } catch (NoSuchMethodException conventionAbsente) {
                // convention absente : la suivante.
            } catch (ReflectiveOperationException poseImpossible) {
                throw new IllegalStateException(
                        "le client ne se pose pas chez le serveur", poseImpossible);
            }
        }
        if (serveur instanceof org.eclipse.lsp4j.services.LanguageClientAware
                conscient) {
            conscient.connect(client);
        }
    }

    @Override
    public void telemetryEvent(Object object) {
        // Aucune télémétrie consommée — le silence est la réponse honnête.
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        for (BiConsumer<String, List<org.eclipse.lsp4j.Diagnostic>> auditeur
                : auditeursDiagnostics) {
            try {
                auditeur.accept(params.getUri(), params.getDiagnostics());
            } catch (RuntimeException auditeurFautif) {
                // Un auditeur fautif n'interrompt ni les autres ni le moteur.
            }
        }
    }

    @Override
    public void logMessage(MessageParams message) {
        if (message == null || message.getMessage() == null) {
            return;
        }
        String type = message.getType() != null
                ? message.getType().name() : "Unknown";
        for (BiConsumer<String, String> auditeur : auditeursJournal) {
            try {
                auditeur.accept(type, message.getMessage());
            } catch (RuntimeException auditeurFautif) {
                // idem : le journal ne casse jamais le moteur.
            }
        }
    }

    @Override
    public void showMessage(MessageParams message) {
        // Les messages modaux passent par le journal (pas de boîte bloquante
        // pendant l'édition — la référence les noie dans la console).
        logMessage(message);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(
            ShowMessageRequestParams requestParams) {
        // Aucune fenêtre modale pendant l'édition : la réponse « aucun choix »
        // (null) est rendue immédiatement — le serveur ne patiente jamais.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> createProgress(
            WorkDoneProgressCreateParams params) {
        // La progression du serveur (workDoneProgress) est servie par l'état
        // d'indexation de l'hôte — le canal client n'a rien à ajouter.
        return CompletableFuture.completedFuture(null);
    }
}
