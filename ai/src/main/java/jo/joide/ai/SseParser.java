package jo.joide.ai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur SSE incrémental (Lot 1) : l'ancienne analyse ligne par ligne à la
 * main cassait sur les commentaires, le CRLF et l'UTF-8 coupé entre deux
 * lectures réseau. Ici : les octets bruts s'accumulent, les lignes se
 * découpent au niveau des octets (un caractère multi-octets UTF-8 ne contient
 * jamais {@code 0x0A} — le découpage est sûr), et chaque ligne complète est
 * décodée en UTF-8.
 *
 * <p>Règles gérées : {@code data:} (préfixe espace optionnel, multi-lignes
 * jointes par {@code \n}), lignes de commentaire ({@code :…}) ignorées,
 * CRLF et LF, champ {@code event}/{@code id}/{@code retry} tolérés (le
 * format passerelle n'utilise que {@code data}). La détection
 * {@code [DONE]} reste à l'appelant (choix de transport, pas de protocole).</p>
 */
public final class SseParser {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final List<String> dataLines = new ArrayList<>();
    private boolean sawAnyField;

    /** Nourrit le parseur avec des octets bruts ; rend les événements complets. */
    public List<String> feed(byte[] bytes, int length) {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            byte b = bytes[i];
            if (b == '\n') {
                completeLine(events);
            } else {
                pending.write(b);
            }
        }
        return events;
    }

    /** Termine un éventuel dernier événement sans saut de ligne final (fin de flux). */
    public List<String> flush() {
        List<String> events = new ArrayList<>();
        if (pending.size() > 0) {
            completeLine(events);
        }
        if (!dataLines.isEmpty()) {
            events.add(joinedData());
        }
        return events;
    }

    /** Une ligne est terminée : commentaire, champ ou fin d'événement. */
    private void completeLine(List<String> events) {
        byte[] lineBytes = pending.toByteArray();
        pending.reset();
        boolean endedWithCr = lineBytes.length > 0
                && lineBytes[lineBytes.length - 1] == '\r';
        String line = new String(lineBytes, 0, endedWithCr
                ? lineBytes.length - 1 : lineBytes.length, StandardCharsets.UTF_8);
        if (line.isEmpty()) {
            // Ligne vide : fin de l'événement courant (les données se rendent).
            if (sawAnyField && !dataLines.isEmpty()) {
                events.add(joinedData());
            }
            dataLines.clear();
            sawAnyField = false;
            return;
        }
        if (line.startsWith(":")) {
            return; // commentaire SSE (keep-alive, « PROCESSING »…) : ignoré
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("data".equals(field)) {
            dataLines.add(value);
            sawAnyField = true;
        }
        // event/id/retry : tolérés, non consommés par le transport actuel.
        else {
            sawAnyField = true;
        }
    }

    /** Données multi-lignes jointes par saut de ligne (règle SSE). */
    private String joinedData() {
        return String.join("\n", dataLines);
    }
}
