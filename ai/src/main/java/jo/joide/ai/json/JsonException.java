package jo.joide.ai.json;

/**
 * Erreur d'analyse JSON avec position (Lot 1) : le message porte la cause et
 * l'index, jamais le document entier (un corps d'erreur HTTP peut contenir
 * des données sensibles).
 */
public final class JsonException extends Exception {

    private final int position;

    public JsonException(String message, int position) {
        super(message + " (position " + position + ")");
        this.position = position;
    }

    /** Position de l'erreur dans le document. */
    public int position() {
        return position;
    }
}
