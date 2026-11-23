package jo.joide.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

/**
 * Le pont client (LOT-11) : les diagnostics publiés par les moteurs
 * rejoignent les auditeurs — un auditeur fautif n'interrompt jamais les
 * autres, le journal passe par le même canal, les requêtes serveur→client
 * répondent le vide honnête (jamais de blocage du moteur).
 */
class LanguageClientBridgeTest {

    @Test
    void diagnosticsPubliésAuxAuditeurs() {
        LanguageClientBridge pont = new LanguageClientBridge();
        AtomicReference<String> uriVue = new AtomicReference<>();
        AtomicReference<Integer> compteVus = new AtomicReference<>();
        pont.onDiagnostics((uri, diagnostics) -> {
            uriVue.set(uri);
            compteVus.set(diagnostics.size());
        });
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri("file:///projet/A.java");
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        diagnostic.setMessage("oups");
        params.setDiagnostics(List.of(diagnostic));
        pont.publishDiagnostics(params);
        assertEquals("file:///projet/A.java", uriVue.get());
        assertEquals(1, compteVus.get());
    }

    @Test
    void auditeurFautifNinterromptPasLesAutres() {
        LanguageClientBridge pont = new LanguageClientBridge();
        AtomicReference<Integer> vu = new AtomicReference<>();
        pont.onDiagnostics((uri, diagnostics) -> {
            throw new IllegalStateException("auditeur cassé");
        });
        pont.onDiagnostics((uri, diagnostics) -> vu.set(diagnostics.size()));
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri("file:///B.java");
        params.setDiagnostics(List.of());
        pont.publishDiagnostics(params);
        assertEquals(0, vu.get());
    }

    @Test
    void laPoignéeRetireLAuditeur() {
        LanguageClientBridge pont = new LanguageClientBridge();
        AtomicReference<Integer> vu = new AtomicReference<>();
        Runnable retrait = pont.onDiagnostics((uri, diagnostics)
                -> vu.set(diagnostics.size()));
        retrait.run();
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri("file:///C.java");
        params.setDiagnostics(List.of());
        pont.publishDiagnostics(params);
        assertEquals(null, vu.get());
    }

    @Test
    void journalEtMessageModalPassentAuCanalJournal() {
        LanguageClientBridge pont = new LanguageClientBridge();
        AtomicReference<String> journal = new AtomicReference<>();
        pont.onLog((type, message) -> journal.set(type + ":" + message));
        MessageParams message = new MessageParams();
        message.setType(MessageType.Warning);
        message.setMessage("attention");
        pont.logMessage(message);
        assertEquals("Warning:attention", journal.get());
        // Le message modal passe par le journal (aucune boîte bloquante).
        pont.showMessage(message);
        assertEquals("Warning:attention", journal.get());
    }

    @Test
    void lesRequêtesServeurRépondentSansBloquer() {
        LanguageClientBridge pont = new LanguageClientBridge();
        // Aucun de ces appels ne lève ni ne bloque : le moteur n'attend rien.
        pont.telemetryEvent(new Object());
        assertEquals(null, pont.showMessageRequest(null).join());
        assertEquals(null, pont.registerCapability(null).join());
        assertEquals(null, pont.unregisterCapability(null).join());
        assertEquals(null, pont.createProgress(null).join());
    }
}
