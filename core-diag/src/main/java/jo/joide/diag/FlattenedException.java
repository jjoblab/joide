package jo.joide.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * Exception aplanie, immuable et <b>dépourvue de tout objet lourd</b> :
 * photographie en chaînes de caractères d'un {@link Throwable}, bornée pour
 * pouvoir vivre dans une entrée de journal ou un rapport de plantage sans en
 * exploser la taille (portage CodeIDE, ADR-021).
 *
 * <p>La chaîne des causes est récursive mais <b>bornée à la construction</b> :
 * une exception cyclique ({@code a} causée par {@code b}, elle-même causée
 * par {@code a}) s'aplatit sans boucle infinie. Les exceptions supprimées
 * ({@link Throwable#getSuppressed() suppressed}) sont aplaties à leur tour,
 * bornées par niveau.</p>
 *
 * @param className  nom qualifié de la classe de l'exception d'origine
 * @param message    message de l'exception tel que capturé à l'aplatissement
 *                   (l'expurgation est appliquée en aval par le pipeline)
 * @param frames     tranches de pile sous forme textuelle, dans l'ordre,
 *                   déjà tronquées au maximum demandé
 * @param cause      cause aplanie suivante dans la chaîne, ou {@code null}
 *                   en fin de chaîne (ou une fois la profondeur atteinte)
 * @param suppressed exceptions supprimées aplaties, bornées par niveau
 */
public record FlattenedException(
        String className,
        String message,
        List<String> frames,
        FlattenedException cause,
        List<FlattenedException> suppressed) {

    /** Tranches de pile conservées par niveau (journalisation, §5.7). */
    public static final int DEFAULT_MAX_FRAMES = 50;

    /** Sauts de cause conservés (§5.7). */
    public static final int DEFAULT_MAX_CAUSES = 10;

    /** Exceptions supprimées conservées par niveau (§5.8). */
    public static final int DEFAULT_MAX_SUPPRESSED = 5;

    public FlattenedException {
        frames = frames == null ? List.of() : List.copyOf(frames);
        suppressed = suppressed == null ? List.of() : List.copyOf(suppressed);
    }

    /**
     * Aplatit un {@link Throwable} en exception bornée — jamais stockée : seules
     * les chaînes traversent.
     *
     * @param throwable exception à photographier
     * @param maxFrames tranches de pile maximales par niveau
     * @param maxCauses sauts de cause maximaux
     * @return l'exception aplatie, prête à être expurgée puis journalisée
     */
    public static FlattenedException from(Throwable throwable, int maxFrames, int maxCauses) {
        return flatten(throwable, maxFrames, maxCauses);
    }

    /** Aplatissement aux bornes nominales du journal. */
    public static FlattenedException from(Throwable throwable) {
        return flatten(throwable, DEFAULT_MAX_FRAMES, DEFAULT_MAX_CAUSES);
    }

    private static FlattenedException flatten(Throwable throwable, int maxFrames, int causesLeft) {
        List<String> frames = new ArrayList<>(Math.min(maxFrames, 16));
        for (StackTraceElement element : throwable.getStackTrace()) {
            if (frames.size() >= maxFrames) {
                break;
            }
            frames.add(element.toString());
        }
        FlattenedException cause = null;
        List<FlattenedException> suppressed = List.of();
        if (causesLeft > 0) {
            if (throwable.getCause() != null) {
                cause = flatten(throwable.getCause(), maxFrames, causesLeft - 1);
            }
            if (throwable.getSuppressed().length > 0) {
                suppressed = new ArrayList<>(Math.min(throwable.getSuppressed().length,
                        DEFAULT_MAX_SUPPRESSED));
                for (Throwable suppressedThrowable : throwable.getSuppressed()) {
                    if (suppressed.size() >= DEFAULT_MAX_SUPPRESSED) {
                        break;
                    }
                    suppressed.add(flatten(suppressedThrowable, maxFrames, causesLeft - 1));
                }
            }
        }
        return new FlattenedException(throwable.getClass().getName(), throwable.getMessage(),
                frames, cause, suppressed);
    }

    /**
     * Recopie l'exception aplatie en transformant chaque message de la chaîne —
     * y compris ceux des exceptions supprimées (les tranches et noms de classe
     * sont inchangés). Utilisé par le pipeline pour appliquer l'expurgation aux
     * messages <b>après</b> aplatissement.
     *
     * @param transform transformation à appliquer ; reçoit et rend
     *        {@code null} pour un message absent (contrat null-safe)
     * @return une copie dont tous les messages sont transformés
     */
    public FlattenedException transformMessages(java.util.function.UnaryOperator<String> transform) {
        return new FlattenedException(className, message == null ? null : transform.apply(message),
                frames, cause == null ? null : cause.transformMessages(transform),
                mapSuppressed(suppressed, transform));
    }

    private static List<FlattenedException> mapSuppressed(List<FlattenedException> list,
            java.util.function.UnaryOperator<String> transform) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<FlattenedException> copy = new ArrayList<>(list.size());
        for (FlattenedException exception : list) {
            copy.add(exception.transformMessages(transform));
        }
        return copy;
    }

    /**
     * Rendu textuel lisible (convention des rapports de plantage) :
     * {@code java.lang.IllegalStateException : message}, tranches indentées,
     * causes préfixées « Causé par : », exceptions supprimées marquées.
     *
     * @return la trace complète sous forme de texte multi-lignes
     */
    public String formatAsText() {
        StringBuilder text = new StringBuilder(256);
        appendTo(text, false);
        return text.toString();
    }

    private void appendTo(StringBuilder text, boolean suppressedMarker) {
        if (suppressedMarker) {
            text.append("[supprimée] ");
        }
        text.append(className);
        if (message != null && !message.isEmpty()) {
            text.append(" : ").append(message);
        }
        for (String frame : frames) {
            text.append("\n  at ").append(frame);
        }
        if (cause != null) {
            text.append("\nCausé par : ");
            cause.appendTo(text, false);
        }
        for (FlattenedException suppressedException : suppressed) {
            text.append('\n');
            suppressedException.appendTo(text, true);
        }
    }
}
