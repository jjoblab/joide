package jo.joide.app.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import jo.celeris.android.host.popups.LanguageSession;
import jo.celeris.android.widgets.CompletionDocModel;
import jo.celeris.android.widgets.SignatureAideModel;
import jo.celeris.core.lsp.types.CodeActionLsp;
import jo.celeris.core.lsp.types.CompletionListLsp;
import jo.celeris.core.lsp.types.DiagnosticLsp;
import jo.celeris.core.lsp.types.FoldLsp;
import jo.celeris.core.lsp.types.FormatOptionsLsp;
import jo.celeris.core.lsp.types.HighlightLsp;
import jo.celeris.core.lsp.types.InlayHintLsp;
import jo.celeris.core.lsp.types.LocationLsp;
import jo.celeris.core.lsp.types.RangeLsp;
import jo.celeris.core.lsp.types.RenamePrepareLsp;
import jo.celeris.core.lsp.types.SemanticTokensLsp;
import jo.celeris.core.lsp.types.SymbolLsp;
import jo.celeris.core.lsp.types.TextEditLsp;
import jo.celeris.core.lsp.types.WorkspaceEditLsp;
import jo.celeris.core.text.Document;
import jo.joide.lsp.LspTypes;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.SelectionRangeParams;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;

/**
 * La session de langage de l'éditeur (porte céleris
 * {@link LanguageSession}) portée par un serveur lsp4j de la brique
 * code-lsp — le pont B-02 côté atelier : la complétion typée, le survol,
 * les signatures, le plan, les plis, les surlignages, les correctifs et
 * les jetons sémantiques des moteurs Java/Kotlin/XML arrivent aux popups
 * de l'éditeur céleris, déjà convertis.
 *
 * <p>Chaque requête part sur le fil unique des serveurs (l'ordre LSP y est
 * garanti — les notifications d'un document ne se doublent jamais) et les
 * réponses traversent {@link LspTypes} (types immuables céleris). Un appel
 * fautif rend le VIDE du domaine — l'éditeur retombe sur ses replis, il
 * ne plante jamais.</p>
 */
public final class EditorLanguageSession implements LanguageSession {

    /** Le serveur branché (initialisé avant la pose). */
    private final LanguageServer serveur;

    /** L'identifiant de langage LSP (java, kotlin, xml). */
    private final String langage;

    /** Le fil unique des requêtes (ordre LSP garanti). */
    private final ExecutorService fil;

    /** La légende des jetons sémantiques (lue à l'initialisation). */
    private final List<String> légendeJetons;

    /** Les versions suivies — la resynchronisation annonce la suivante. */
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();

    /** Le service de documents du serveur (câblé une fois). */
    private final TextDocumentService documents;

    /** L'auditeur des diagnostics (posé par l'atelier après le branchement). */
    private volatile java.util.function.BiConsumer<String, List<DiagnosticLsp>>
            auditeurDiagnostics;

    public EditorLanguageSession(LanguageServer serveur, String langage,
            ExecutorService fil, List<String> légendeJetons) {
        this.serveur = serveur;
        this.langage = langage;
        this.fil = fil;
        this.légendeJetons = List.copyOf(légendeJetons);
        this.documents = serveur.getTextDocumentService();
    }

    // ---- cycle de vie des documents ----------------------------------------

    @Override
    public void open(String uri, String language, String text) {
        int version = versions.getOrDefault(uri, 0) + 1;
        versions.put(uri, version);
        TextDocumentItem document = new TextDocumentItem(uri, langage, version,
                text != null ? text : "");
        documents.didOpen(new DidOpenTextDocumentParams(document));
    }

    @Override
    public void synchroniser(String uri, String text) {
        int version = versions.getOrDefault(uri, 0) + 1;
        versions.put(uri, version);
        VersionedTextDocumentIdentifier document =
                new VersionedTextDocumentIdentifier(uri, version);
        DidChangeTextDocumentParams paramètres =
                new DidChangeTextDocumentParams(document,
                        List.of(new TextDocumentContentChangeEvent(
                                text != null ? text : "")));
        documents.didChange(paramètres);
    }

    // ---- les trois canaux des popups ---------------------------------------

    @Override
    public CompletableFuture<SignatureAideModel> signature(String uri,
            Document doc, int caret) {
        SignatureHelpParams paramètres = new SignatureHelpParams(
                identifiant(uri), positionLsp(doc, caret));
        return appel(() -> documents.signatureHelp(paramètres), null)
                .thenApply(LspTypes::signature)
                .thenApply(aide -> {
                    if (aide == null || aide.entrees().isEmpty()) {
                        return null;
                    }
                    List<SignatureAideModel.Entry> entrées = new ArrayList<>();
                    for (var source : aide.entrees()) {
                        List<String> paramètresModèle = new ArrayList<>();
                        for (var paramètre : source.parameters()) {
                            paramètresModèle.add(paramètre.label());
                        }
                        entrées.add(new SignatureAideModel.Entry(source.label(),
                                paramètresModèle, source.documentation()));
                    }
                    return SignatureAideModel.of(entrées,
                            aide.signatureActiveIndex(),
                            aide.parametreCourant());
                });
    }

    @Override
    public CompletableFuture<CompletionDocModel> hover(String uri,
            Document doc, String symbole, int caret) {
        HoverParams paramètres = new HoverParams(identifiant(uri),
                positionLsp(doc, caret));
        return appel(() -> documents.hover(paramètres), null)
                .thenApply(LspTypes::hover)
                .thenApply(survol -> survol == null ? null
                        : CompletionDocModel.of(survol.text(), survol.text()));
    }

    @Override
    public boolean supportsCompletion() {
        return true;
    }

    @Override
    public CompletableFuture<CompletionListLsp> completion(String uri,
            Document doc, int caret) {
        CompletionParams paramètres = new CompletionParams(identifiant(uri),
                positionLsp(doc, caret));
        return appel(() -> documents.completion(paramètres), null)
                .thenApply(réponse -> {
                    if (réponse == null) {
                        return CompletionListLsp.of(true, List.of());
                    }
                    return réponse.isLeft()
                            ? LspTypes.completion(listeDe(réponse.getLeft()))
                            : LspTypes.completion(réponse.getRight());
                });
    }

    @Override
    public void setDiagnosticsListener(
            java.util.function.BiConsumer<String, List<DiagnosticLsp>> auditeur) {
        this.auditeurDiagnostics = auditeur;
    }

    // ---- la couverture du protocole ----------------------------------------

    @Override
    public CompletableFuture<List<SymbolLsp>> symbols(String uri) {
        return appel(() -> documents.documentSymbol(
                        new DocumentSymbolParams(identifiant(uri))), List.of())
                .thenApply(LspTypes::symbols);
    }

    @Override
    public CompletableFuture<List<FoldLsp>> folds(String uri) {
        return appel(() -> documents.foldingRange(
                        new FoldingRangeRequestParams(identifiant(uri))), List.of())
                .thenApply(LspTypes::folds);
    }

    @Override
    public CompletableFuture<List<TextEditLsp>> formatage(String uri,
            FormatOptionsLsp options) {
        DocumentFormattingParams paramètres =
                new DocumentFormattingParams(identifiant(uri),
                        LspTypes.options(options));
        return appel(() -> documents.formatting(paramètres), List.of())
                .thenApply(LspTypes::edits);
    }

    @Override
    public CompletableFuture<List<TextEditLsp>> rangeFormatting(String uri,
            RangeLsp range, FormatOptionsLsp options) {
        DocumentRangeFormattingParams paramètres =
                new DocumentRangeFormattingParams(identifiant(uri),
                        LspTypes.options(options), plageLsp(range));
        return appel(() -> documents.rangeFormatting(paramètres), List.of())
                .thenApply(LspTypes::edits);
    }

    @Override
    public CompletableFuture<List<HighlightLsp>> highlights(String uri,
            Document doc, int caret) {
        return appel(() -> documents.documentHighlight(
                        new DocumentHighlightParams(identifiant(uri),
                                positionLsp(doc, caret))), List.of())
                .thenApply(LspTypes::highlights);
    }

    @Override
    public CompletableFuture<List<RangeLsp>> selectionRanges(String uri,
            Document doc, int caret) {
        SelectionRangeParams paramètres = new SelectionRangeParams(
                identifiant(uri), List.of(positionLsp(doc, caret)));
        return appel(() -> documents.selectionRange(paramètres), List.of())
                .thenApply(plages -> {
                    List<RangeLsp> converties = new ArrayList<>();
                    if (plages != null) {
                        for (var plage : plages) {
                            if (plage != null && plage.getRange() != null) {
                                converties.add(LspTypes.range(plage.getRange()));
                            }
                        }
                    }
                    return converties;
                });
    }

    @Override
    public CompletableFuture<RenamePrepareLsp> preparerRennomage(
            String uri, Document doc, int caret) {
        PrepareRenameParams paramètres = new PrepareRenameParams(
                identifiant(uri), positionLsp(doc, caret));
        return appel(() -> documents.prepareRename(paramètres), null)
                .thenApply(LspTypes::renamePrepare);
    }

    @Override
    public CompletableFuture<WorkspaceEditLsp> rename(String uri, Document doc,
            int caret, String nouveauNom) {
        org.eclipse.lsp4j.RenameParams paramètres =
                new org.eclipse.lsp4j.RenameParams();
        paramètres.setTextDocument(identifiant(uri));
        paramètres.setPosition(positionLsp(doc, caret));
        paramètres.setNewName(nouveauNom);
        return appel(() -> documents.rename(paramètres),
                        (org.eclipse.lsp4j.WorkspaceEdit) null)
                .thenApply(LspTypes::workspaceEdit);
    }

    @Override
    public CompletableFuture<List<CodeActionLsp>> correctifs(String uri,
            RangeLsp range) {
        CodeActionParams paramètres = new CodeActionParams(identifiant(uri),
                plageLsp(range), new CodeActionContext(List.of()));
        return appel(() -> documents.codeAction(paramètres), List.of())
                .thenApply(actions -> {
                    List<CodeActionLsp> convertis = new ArrayList<>();
                    if (actions != null) {
                        for (var action : actions) {
                            CodeActionLsp converti = null;
                            if (action != null && action.isLeft()) {
                                converti = LspTypes.codeActionCommande(
                                        action.getLeft());
                            } else if (action != null && action.isRight()) {
                                converti = LspTypes.codeAction(action.getRight());
                            }
                            if (converti != null) {
                                convertis.add(converti);
                            }
                        }
                    }
                    return convertis;
                });
    }

    @Override
    public CompletableFuture<List<InlayHintLsp>> inlayHints(String uri,
            RangeLsp range) {
        InlayHintParams paramètres = new InlayHintParams(identifiant(uri),
                plageLsp(range));
        return appel(() -> documents.inlayHint(paramètres), List.of())
                .thenApply(LspTypes::inlayHints);
    }

    @Override
    public CompletableFuture<SemanticTokensLsp> tokens(String uri) {
        return appel(() -> documents.semanticTokensFull(
                        new SemanticTokensParams(identifiant(uri))),
                        (org.eclipse.lsp4j.SemanticTokens) null)
                .thenApply(LspTypes::tokens);
    }

    @Override
    public CompletableFuture<SemanticTokensLsp> tokensRange(String uri,
            RangeLsp range) {
        return appel(() -> documents.semanticTokensRange(
                        new SemanticTokensRangeParams(identifiant(uri),
                                plageLsp(range))),
                        (org.eclipse.lsp4j.SemanticTokens) null)
                .thenApply(LspTypes::tokens);
    }

    @Override
    public List<String> tokenTypes() {
        return légendeJetons;
    }

    @Override
    public CompletableFuture<List<LocationLsp>> references(String uri,
            Document doc, int caret) {
        ReferenceParams paramètres = new ReferenceParams();
        paramètres.setTextDocument(identifiant(uri));
        paramètres.setPosition(positionLsp(doc, caret));
        paramètres.setContext(new ReferenceContext(true));
        return appel(() -> documents.references(paramètres), List.of())
                .thenApply(LspTypes::locations);
    }

    @Override
    public void close() {
        // Le serveur appartient aux sessions du projet (LspSessions.close y
        // pourvoit) : la session d'édition ne coupe que son auditeur.
        auditeurDiagnostics = null;
    }

    // ---- aides ----------------------------------------------------------------

    /** La position LSP d'un caret céleris. */
    private static Position positionLsp(Document doc, int caret) {
        jo.celeris.core.lsp.types.LspPosition position =
                LspTypes.position(doc, caret);
        return new Position(position.line(), position.column());
    }

    /** La plage lsp4j d'une plage céleris. */
    private static org.eclipse.lsp4j.Range plageLsp(RangeLsp plage) {
        return new org.eclipse.lsp4j.Range(
                new Position(plage.start().line(), plage.start().column()),
                new Position(plage.end().line(), plage.end().column()));
    }

    private static TextDocumentIdentifier identifiant(String uri) {
        return new TextDocumentIdentifier(uri);
    }

    /** La liste de complétion de la forme « liste plate » du protocole. */
    private static org.eclipse.lsp4j.CompletionList listeDe(
            List<org.eclipse.lsp4j.CompletionItem> items) {
        org.eclipse.lsp4j.CompletionList liste =
                new org.eclipse.lsp4j.CompletionList();
        liste.setIsIncomplete(true); // liste partielle par prudence : l'ordre est honoré
        liste.setItems(items != null ? items : List.of());
        return liste;
    }

    /**
     * Un appel sur le fil des serveurs — un échec (serveur muet, requête
     * fautive) rend le REPLI du domaine : l'éditeur ne plante jamais, il
     * retombe sur ses propres ressources.
     */
    private <T> CompletableFuture<T> appel(
            Supplier<CompletableFuture<T>> requête, T repli) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletableFuture<T> avenir = requête.get();
                return avenir != null ? avenir : CompletableFuture.<T>failedFuture(
                        new IllegalStateException("réponse nulle"));
            } catch (RuntimeException appelFautif) {
                return CompletableFuture.<T>failedFuture(appelFautif);
            }
        }, fil).thenCompose(avoir -> avoir).exceptionally(échec -> repli);
    }
}
