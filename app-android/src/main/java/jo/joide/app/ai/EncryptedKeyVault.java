package jo.joide.app.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import jo.joide.ai.KeyVault;

/**
 * Coffre des clés IA de la coquille (conception §8, écart voulu REF-123) :
 * EncryptedSharedPreferences (clé tenue par Android Keystore) ; **repli
 * mémoire** si le Keystore est indisponible — les clés ne tiennent pas entre
 * les lancements, mais jamais de stockage en clair. Jamais dans les logs.
 */
public final class EncryptedKeyVault implements KeyVault {

    private static final String TAG = "EncryptedKeyVault";

    private final SharedPreferences preferences;
    private final KeyVault fallback;

    /** Ouvre le coffre chiffré (repli mémoire si Keystore indisponible). */
    public EncryptedKeyVault(Context context) {
        SharedPreferences opened = null;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            opened = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    "joide_coffre_ia",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Keystore indisponible : repli mémoire honnête (la clé ne survit pas
            // au redémarrage) — jamais un stockage en clair sur disque.
            Log.w(TAG, "Keystore indisponible : repli mémoire du coffre IA");
        }
        this.preferences = opened;
        this.fallback = new KeyVault.InMemory();
    }

    @Override
    public String apiKey(String providerId) {
        return vault().apiKey(providerId);
    }

    @Override
    public void putApiKey(String providerId, String apiKey) {
        vault().putApiKey(providerId, apiKey);
    }

    @Override
    public void removeApiKey(String providerId) {
        vault().removeApiKey(providerId);
    }

    @Override
    public boolean isEmpty() {
        return vault().isEmpty();
    }

    private KeyVault vault() {
        return preferences != null ? encryptedAdapter() : fallback;
    }

    /** Adaptateur du coffre chiffré au port (clés : providerId → valeur). */
    private KeyVault encryptedAdapter() {
        return new KeyVault() {
            @Override
            public String apiKey(String providerId) {
                String value = preferences.getString(providerId, null);
                return value;
            }

            @Override
            public void putApiKey(String providerId, String apiKey) {
                preferences.edit().putString(providerId, apiKey).apply();
            }

            @Override
            public void removeApiKey(String providerId) {
                preferences.edit().remove(providerId).apply();
            }

            @Override
            public boolean isEmpty() {
                return preferences.getAll().isEmpty();
            }
        };
    }
}
