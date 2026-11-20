package jo.joide.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coffre des clés API IA (conception §8, écart voulu REF-123) : les clés sont
 * **chiffrées au repos** — l'implémentation de production (coquille Android)
 * utilise EncryptedSharedPreferences (clé tenue par Android Keystore) ; si le
 * Keystore est indisponible, **repli en mémoire** (les clés ne tiennent pas
 * entre les lancements) plutôt qu'un stockage en clair. Jamais dans les logs ;
 * la rédaction des rapports les masque (§10).
 *
 * <p>Port pur : la coquille branche son implémentation ; les tests utilisent
 * le repli mémoire, qui suit le même contrat.</p>
 */
public interface KeyVault {

    /** Clé du fournisseur (nulle si jamais posée). */
    String apiKey(String providerId);

    /** Pose (ou remplace) la clé d'un fournisseur. */
    void putApiKey(String providerId, String apiKey);

    /** Oublie la clé d'un fournisseur. */
    void removeApiKey(String providerId);

    /** Aucune clé posée ? */
    boolean isEmpty();

    /**
     * Repli mémoire : les clés vivent dans la carte du processus seulement.
     * Utilisé quand le Keystore est indisponible (conception §8) et par les
     * tests JVM — le contrat est identique, la persistance non garantie.
     */
    final class InMemory implements KeyVault {

        private final Map<String, String> keys = new LinkedHashMap<>();

        @Override
        public synchronized String apiKey(String providerId) {
            return keys.get(providerId);
        }

        @Override
        public synchronized void putApiKey(String providerId, String apiKey) {
            keys.put(providerId, apiKey);
        }

        @Override
        public synchronized void removeApiKey(String providerId) {
            keys.remove(providerId);
        }

        @Override
        public synchronized boolean isEmpty() {
            return keys.isEmpty();
        }
    }
}
