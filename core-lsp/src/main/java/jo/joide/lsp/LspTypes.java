package jo.joide.lsp;

import java.util.ArrayList;
import java.util.List;
import jo.celeris.core.lsp.types.CodeActionLsp;
import jo.celeris.core.lsp.types.CompletionItemLsp;
import jo.celeris.core.lsp.types.CompletionListLsp;
import jo.celeris.core.lsp.types.DiagnosticLsp;
import jo.celeris.core.lsp.types.DocumentEditLsp;
import jo.celeris.core.lsp.types.FoldLsp;
import jo.celeris.core.lsp.types.FormatOptionsLsp;
import jo.celeris.core.lsp.types.HighlightLsp;
import jo.celeris.core.lsp.types.HoverLsp;
import jo.celeris.core.lsp.types.InlayHintLsp;
import jo.celeris.core.lsp.types.LocationLsp;
import jo.celeris.core.lsp.types.LspPosition;
import jo.celeris.core.lsp.types.RangeLsp;
import jo.celeris.core.lsp.types.RenamePrepareLsp;
import jo.celeris.core.lsp.types.SemanticTokensLsp;
import jo.celeris.core.lsp.types.SignatureLsp;
import jo.celeris.core.lsp.types.SymbolLsp;
import jo.celeris.core.lsp.types.TextEditLsp;
import jo.celeris.core.lsp.types.WorkspaceEditLsp;
import jo.celeris.core.text.Document;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;

/**
 * La conversion des types lsp4j 0.22.0 vers les types immuables de céleris
 * (jo.celeris.core.lsp.types) — la moitié « entrantes » du pont B-02.
 *
 * <p>Règles de la frontière (PIEGE-18 d'ADR-005 céleris : les diagnostics
 * et les jetons sont deux flux INDÉPENDANTS) :
 * <ul>
 *   <li>chaque conversion est une fonction PURE — null rend null, une
 *       liste vide rend la liste vide du domaine, jamais de contenu
 *       fabriqué (convention I9 du moteur : « une liste vide EST une
 *       réponse ») ;</li>
 *   <li>les positions LSP (UTF-16, 0-based) traversent par
 *       {@link #position(Document, int)} / {@link #offset(Document, LspPosition)}
 *       — la transposition explicite d'ADR-003 céleris ;</li>
 *   <li>le delta des jetons sémantiques se décode par
 *       {@link SemanticTokensLsp#of} (relatif→absolu).</li>
 * </ul></p>
 */
public final class LspTypes {

    private LspTypes() {}

    // ---- positions ------------------------------------------------------------

    /** La position LSP d'un offset du document (0-based, colonne en UTF-16). */
    public static LspPosition position(Document doc, int offset) {
        int borne = Math.max(0, Math.min(offset, doc.length()));
        int line = doc.lineForOffset(borne);
        return new LspPosition(line, borne - doc.lineStart(line));
    }

    /** La plage LSP d'une étendue du document. */
    public static RangeLsp range(Document doc, int start, int end) {
        return new RangeLsp(position(doc, start), position(doc, end));
    }

    /** L'offset d'une position LSP (0-based) dans le document — borné. */
    public static int offset(Document doc, LspPosition position) {
        int line = Math.max(0, Math.min(position.line(), doc.lineCount() - 1));
        int début = doc.lineStart(line);
        int fin = doc.lineEnd(line);
        return Math.max(début, Math.min(début + position.column(), fin));
    }

    /** La plage lsp4j en plage céleris. */
    public static RangeLsp range(Range plage) {
        return plage == null ? null
                : new RangeLsp(position(plage.getStart()), position(plage.getEnd()));
    }

    private static LspPosition position(Position position) {
        return position == null ? new LspPosition(0, 0)
                : new LspPosition(position.getLine(), position.getCharacter());
    }

    // ---- diagnostics ----------------------------------------------------------

    /** Un diagnostic lsp4j en diagnostic céleris (gravité 1-4 du protocole). */
    public static DiagnosticLsp diagnostic(Diagnostic diagnostic) {
        if (diagnostic == null) {
            return null;
        }
        return new DiagnosticLsp(range(diagnostic.getRange()),
                gravité(diagnostic),
                diagnostic.getMessage() != null ? diagnostic.getMessage() : "",
                code(diagnostic.getCode()),
                diagnostic.getSource());
    }

    private static int gravité(Diagnostic diagnostic) {
        // Gravité du protocole (1 erreur, 2 avertissement, 3 info, 4 indice) ;
        // absente : 3 (information) — la valeur par défaut honnête du protocole.
        return diagnostic.getSeverity() != null
                ? diagnostic.getSeverity().getValue() : 3;
    }

    private static String code(Either<String, Integer> code) {
        if (code == null) {
            return null;
        }
        return code.isLeft() ? code.getLeft()
                : code.getRight() != null ? String.valueOf(code.getRight()) : null;
    }

    /** La liste des diagnostics convertis (nulls filtrés, ordre conservé). */
    public static List<DiagnosticLsp> diagnostics(List<Diagnostic> diagnostics) {
        List<DiagnosticLsp> convertis = new ArrayList<>();
        if (diagnostics != null) {
            for (Diagnostic diagnostic : diagnostics) {
                DiagnosticLsp converti = diagnostic(diagnostic);
                if (converti != null) {
                    convertis.add(converti);
                }
            }
        }
        return convertis;
    }

    // ---- complétion -----------------------------------------------------------

    /** La liste de complétion lsp4j en liste céleris — l'ordre du serveur. */
    public static CompletionListLsp completion(CompletionList liste) {
        if (liste == null) {
            return CompletionListLsp.of(true, List.of());
        }
        List<CompletionItemLsp> éléments = new ArrayList<>();
        if (liste.getItems() != null) {
            for (CompletionItem item : liste.getItems()) {
                CompletionItemLsp converti = item(item);
                if (converti != null) {
                    éléments.add(converti);
                }
            }
        }
        return CompletionListLsp.of(liste.isIncomplete(), éléments);
    }

    /** Un item de complétion lsp4j en item céleris. */
    public static CompletionItemLsp item(CompletionItem item) {
        if (item == null || item.getLabel() == null) {
            return null;
        }
        return new CompletionItemLsp(
                item.getLabel(),
                item.getKind() != null ? item.getKind().getValue() : 0,
                item.getDetail(),
                documentation(item.getDocumentation()),
                item.getSortText(),
                item.getFilterText(),
                item.getInsertText(),
                Boolean.TRUE.equals(item.getDeprecated()));
    }

    private static String documentation(Either<String, MarkupContent> doc) {
        if (doc == null) {
            return null;
        }
        if (doc.isLeft()) {
            return doc.getLeft();
        }
        MarkupContent contenu = doc.getRight();
        return contenu != null ? contenu.getValue() : null;
    }

    // ---- survol et signatures -------------------------------------------------

    /** Le survol lsp4j en survol céleris — null quand le serveur ne dit rien. */
    public static HoverLsp hover(Hover survol) {
        if (survol == null || survol.getContents() == null) {
            return null;
        }
        String texte = contenuSurvol(survol.getContents());
        if (texte == null || texte.isBlank()) {
            return null;
        }
        return new HoverLsp(texte, "markdown", range(survol.getRange()));
    }

    /** Le contenu d'un survol : la forme riche (MarkupContent) ou la liste
     * de fragments (chaînes ou MarkedString du protocole historique). */
    private static String contenuSurvol(
            Either<List<Either<String, org.eclipse.lsp4j.MarkedString>>, MarkupContent>
                    contents) {
        if (contents.isRight()) {
            MarkupContent riche = contents.getRight();
            return riche != null ? riche.getValue() : null;
        }
        List<Either<String, org.eclipse.lsp4j.MarkedString>> listés = contents.getLeft();
        if (listés == null || listés.isEmpty()) {
            return null;
        }
        StringBuilder texte = new StringBuilder();
        for (Either<String, org.eclipse.lsp4j.MarkedString> partie : listés) {
            String morceau = partie.isLeft() ? partie.getLeft()
                    : partie.getRight() != null ? partie.getRight().getValue() : null;
            if (morceau != null && !morceau.isBlank()) {
                if (texte.length() > 0) {
                    texte.append("\n\n");
                }
                texte.append(morceau);
            }
        }
        return texte.length() > 0 ? texte.toString() : null;
    }

    /** L'aide aux signatures lsp4j en signatures céleris — null sans entrée. */
    public static SignatureLsp signature(SignatureHelp aide) {
        if (aide == null || aide.getSignatures() == null
                || aide.getSignatures().isEmpty()) {
            return null;
        }
        List<SignatureLsp.Entry> entrées = new ArrayList<>();
        for (SignatureInformation information : aide.getSignatures()) {
            if (information == null) {
                continue;
            }
            List<SignatureLsp.Parameter> paramètres = new ArrayList<>();
            if (information.getParameters() != null) {
                for (var paramètre : information.getParameters()) {
                    if (paramètre != null) {
                        paramètres.add(new SignatureLsp.Parameter(
                                libelléParamètre(paramètre.getLabel()),
                                documentation(paramètre.getDocumentation())));
                    }
                }
            }
            entrées.add(new SignatureLsp.Entry(information.getLabel(),
                    paramètres, documentation(information.getDocumentation())));
        }
        if (entrées.isEmpty()) {
            return null;
        }
        int active = aide.getActiveSignature() != null
                ? aide.getActiveSignature() : -1;
        int courant = aide.getActiveParameter() != null
                ? aide.getActiveParameter() : -1;
        return SignatureLsp.of(entrées, active, courant);
    }

    private static String libelléParamètre(
            Either<String, org.eclipse.lsp4j.jsonrpc.messages.Tuple.Two<Integer, Integer>> label) {
        if (label == null) {
            return "";
        }
        if (label.isLeft()) {
            return label.getLeft();
        }
        // La forme « bornes » [début, fin[ du protocole : rendue lisible.
        org.eclipse.lsp4j.jsonrpc.messages.Tuple.Two<Integer, Integer> bornes = label.getRight();
        return bornes != null ? "[" + bornes.getFirst() + "," + bornes.getSecond() + ")"
                : "";
    }

    // ---- plan, plis, éditions -------------------------------------------------

    /** Les symboles du document lsp4j en plan céleris — les deux formes du
     * protocole (hiérarchique DocumentSymbol, plate SymbolInformation). */
    public static List<SymbolLsp> symbols(
            List<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> symboles) {
        List<SymbolLsp> convertis = new ArrayList<>();
        if (symboles != null) {
            for (Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol> forme
                    : symboles) {
                SymbolLsp converti = null;
                if (forme != null && forme.isRight()) {
                    converti = symbol(forme.getRight());
                } else if (forme != null && forme.isLeft()
                        && forme.getLeft() != null) {
                    org.eclipse.lsp4j.SymbolInformation plat = forme.getLeft();
                    converti = new SymbolLsp(plat.getName(),
                            plat.getKind() != null ? plat.getKind().getValue() : 0,
                            range(plat.getLocation() != null
                                    ? plat.getLocation().getRange() : null),
                            null, List.of());
                }

                if (converti != null) {
                    convertis.add(converti);
                }
            }
        }
        return convertis;
    }

    private static SymbolLsp symbol(DocumentSymbol symbole) {
        if (symbole == null || symbole.getName() == null) {
            return null;
        }
        return new SymbolLsp(symbole.getName(),
                symbole.getKind() != null ? symbole.getKind().getValue() : 0,
                range(symbole.getRange()),
                range(symbole.getSelectionRange()),
                symbolsPurs(symbole.getChildren()));
    }

    /** Les enfants d'un symbole hiérarchique : la liste pure du protocole. */
    private static List<SymbolLsp> symbolsPurs(List<DocumentSymbol> symboles) {
        List<SymbolLsp> convertis = new ArrayList<>();
        if (symboles != null) {
            for (DocumentSymbol symbole : symboles) {
                SymbolLsp converti = symbol(symbole);
                if (converti != null) {
                    convertis.add(converti);
                }
            }
        }
        return convertis;
    }

    /** Les plis lsp4j en plis céleris. */
    public static List<FoldLsp> folds(List<? extends FoldingRange> plis) {
        List<FoldLsp> convertis = new ArrayList<>();
        if (plis != null) {
            for (FoldingRange pli : plis) {
                if (pli != null) {
                    convertis.add(new FoldLsp(pli.getStartLine(), pli.getEndLine()));
                }
            }
        }
        return convertis;
    }

    /** Un édit de texte lsp4j en édit céleris. */
    public static TextEditLsp edit(TextEdit édit) {
        if (édit == null) {
            return null;
        }
        return new TextEditLsp(range(édit.getRange()),
                édit.getNewText() != null ? édit.getNewText() : "");
    }

    /** Les édits de formatage lsp4j en édits céleris. */
    public static List<TextEditLsp> edits(List<? extends TextEdit> édits) {
        List<TextEditLsp> convertis = new ArrayList<>();
        if (édits != null) {
            for (TextEdit édit : édits) {
                TextEditLsp converti = edit(édit);
                if (converti != null) {
                    convertis.add(converti);
                }
            }
        }
        return convertis;
    }

    /** L'édition d'espace lsp4j (documentChanges) en édition céleris. */
    public static WorkspaceEditLsp workspaceEdit(WorkspaceEdit édition) {
        if (édition == null) {
            return WorkspaceEditLsp.EMPTY;
        }
        List<DocumentEditLsp> documents = new ArrayList<>();
        if (édition.getDocumentChanges() != null) {
            for (Either<TextDocumentEdit, ResourceOperation> changement
                    : édition.getDocumentChanges()) {
                if (changement != null && changement.isLeft()) {
                    TextDocumentEdit éditDocument = changement.getLeft();
                    documents.add(new DocumentEditLsp(
                            éditDocument.getTextDocument().getUri(),
                            éditDocument.getTextDocument().getVersion() != null
                                    ? éditDocument.getTextDocument().getVersion() : 0,
                            edits(éditDocument.getEdits())));
                }
            }
        }
        if (documents.isEmpty() && édition.getChanges() != null) {
            for (var entrée : édition.getChanges().entrySet()) {
                documents.add(new DocumentEditLsp(entrée.getKey(), 0,
                        edits(entrée.getValue())));
            }
        }
        return new WorkspaceEditLsp(documents, List.of());
    }

    // ---- navigation, surlignages, indices, jetons -----------------------------

    /** Une localisation lsp4j en localisation céleris. */
    public static LocationLsp location(Location localisation) {
        if (localisation == null) {
            return null;
        }
        return new LocationLsp(localisation.getUri(),
                range(localisation.getRange()));
    }

    /** Les localisations lsp4j en localisations céleris (forme wildcard). */
    public static List<LocationLsp> locations(List<? extends Location> localisations) {
        List<LocationLsp> converties = new ArrayList<>();
        if (localisations != null) {
            for (Location localisation : localisations) {
                LocationLsp convertie = location(localisation);
                if (convertie != null) {
                    converties.add(convertie);
                }
            }
        }
        return converties;
    }

    /** Les surlignages lsp4j en surlignages céleris. */
    public static List<HighlightLsp> highlights(List<? extends DocumentHighlight> surlignages) {
        List<HighlightLsp> convertis = new ArrayList<>();
        if (surlignages != null) {
            for (DocumentHighlight surlignage : surlignages) {
                if (surlignage != null) {
                    convertis.add(new HighlightLsp(range(surlignage.getRange()),
                            surlignage.getKind() != null
                                    ? surlignage.getKind().getValue() : 0));
                }
            }
        }
        return convertis;
    }

    /** Les indices en ligne lsp4j en indices céleris. */
    public static List<InlayHintLsp> inlayHints(List<? extends InlayHint> indices) {
        List<InlayHintLsp> convertis = new ArrayList<>();
        if (indices != null) {
            for (InlayHint indice : indices) {
                if (indice == null) {
                    continue;
                }
                convertis.add(new InlayHintLsp(position(indice.getPosition()),
                        parties(indice.getLabel()), 0, false, false));
            }
        }
        return convertis;
    }

    /** Le libellé d'un indice : la chaîne seule ou ses parties typées. */
    private static List<InlayHintLsp.Part> parties(
            Either<String, List<InlayHintLabelPart>> libellé) {
        List<InlayHintLsp.Part> parties = new ArrayList<>();
        if (libellé == null) {
            return parties;
        }
        if (libellé.isLeft()) {
            if (libellé.getLeft() != null) {
                parties.add(new InlayHintLsp.Part(libellé.getLeft(), null));
            }
            return parties;
        }
        if (libellé.getRight() != null) {
            for (InlayHintLabelPart partie : libellé.getRight()) {
                if (partie != null) {
                    parties.add(new InlayHintLsp.Part(partie.getValue(), null));
                }
            }
        }
        return parties;
    }

    /** Les jetons sémantiques lsp4j (delta encodé) en jetons céleris décodés. */
    public static SemanticTokensLsp tokens(org.eclipse.lsp4j.SemanticTokens jetons) {
        if (jetons == null || jetons.getData() == null) {
            return SemanticTokensLsp.of(null, List.of());
        }
        return SemanticTokensLsp.of(jetons.getResultId(), jetons.getData());
    }

    /** La préparation de renommage lsp4j en préparation céleris — null si le
     * serveur ne prépare pas (comportement par défaut : renommage permis). */
    public static RenamePrepareLsp renamePrepare(
            Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> préparation) {
        if (préparation == null) {
            return null;
        }
        if (préparation.isFirst()) {
            return new RenamePrepareLsp(range(préparation.getFirst()), null);
        }
        if (préparation.isSecond() && préparation.getSecond() != null) {
            return new RenamePrepareLsp(range(préparation.getSecond().getRange()),
                    préparation.getSecond().getPlaceholder());
        }
        return null;
    }

    /** Les options de formatage céleris en options lsp4j. */
    public static org.eclipse.lsp4j.FormattingOptions options(FormatOptionsLsp options) {
        return new org.eclipse.lsp4j.FormattingOptions(options.tabWidth(),
                options.espaces());
    }

    /** Le paramètre de renommage lsp4j (fabrique locale). */
    public static RenameParams renameParams(String uri, LspPosition position,
            String nouveauNom) {
        RenameParams paramètres = new RenameParams();
        paramètres.setTextDocument(
                new org.eclipse.lsp4j.TextDocumentIdentifier(uri));
        paramètres.setPosition(new Position(position.line(), position.column()));
        paramètres.setNewName(nouveauNom);
        return paramètres;
    }

    /** Un correctif rapide lsp4j en correctif céleris (titre + édition). */
    public static CodeActionLsp codeAction(org.eclipse.lsp4j.CodeAction action) {
        if (action == null || action.getTitle() == null) {
            return null;
        }
        return new CodeActionLsp(action.getTitle(),
                workspaceEdit(action.getEdit()));
    }

    /** La forme Command d'un correctif (l'autre bras du Either lsp4j) :
     * un titre sans édition — l'espace vide du domaine. */
    public static CodeActionLsp codeActionCommande(org.eclipse.lsp4j.Command commande) {
        if (commande == null || commande.getTitle() == null) {
            return null;
        }
        return new CodeActionLsp(commande.getTitle(), WorkspaceEditLsp.EMPTY);
    }
}
