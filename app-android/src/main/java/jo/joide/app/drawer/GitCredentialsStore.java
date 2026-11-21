package jo.joide.app.drawer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import jo.joide.coregit.GitOps;
import java.util.HashMap;
import java.util.Map;

/**
 * Coffre des jetons git (Lot 7 du tiroir) : identifiants distants **par
 * hôte**, dans EncryptedSharedPreferences (clé tenue par Android Keystore),
 * même mécanique que le coffre IA ; **repli mémoire** si le Keystore est
 * indisponible — le jeton ne survit pas au redémarrage, mais jamais de
 * stockage en clair sur disque. Jamais dans les logs.
 */
public final class GitCredentialsStore {

    private static final String TAG = "GitCredentialsStore";
    private static final String FILE = "joide_coffre_git";

    private final SharedPreferences encrypted;
    private final Map<String, String> fallback = new HashMap<>();

    public GitCredentialsStore(Context context) {
        SharedPreferences opened = null;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            opened = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.w(TAG, "Keystore indisponible : repli mémoire du coffre git");
        }
        this.encrypted = opened;
    }

    /** Identifiants d'un hôte (null si aucun jeton stocké). */
    public GitOps.Credentials of(String remoteUrl) {
        String host = hostOf(remoteUrl);
        if (host == null) {
            return null;
        }
        String username = read("user@" + host);
        String token = read("token@" + host);
        return token == null || token.isEmpty()
                ? null : new GitOps.Credentials(username, token);
    }

    /** Pose les identifiants d'un hôte. */
    public void put(String remoteUrl, String username, String token) {
        String host = hostOf(remoteUrl);
        if (host == null) {
            return;
        }
        write("user@" + host, username == null ? "" : username);
        write("token@" + host, token == null ? "" : token);
    }

    /** Oublie les identifiants d'un hôte. */
    public void remove(String remoteUrl) {
        String host = hostOf(remoteUrl);
        if (host == null) {
            return;
        }
        write("user@" + host, null);
        write("token@" + host, null);
    }

    private String read(String key) {
        if (encrypted != null) {
            return encrypted.getString(key, null);
        }
        return fallback.get(key);
    }

    private void write(String key, String value) {
        if (encrypted != null) {
            if (value == null) {
                encrypted.edit().remove(key).apply();
            } else {
                encrypted.edit().putString(key, value).apply();
            }
            return;
        }
        if (value == null) {
            fallback.remove(key);
        } else {
            fallback.put(key, value);
        }
    }

    /** Hôte d'une URL distante (https://x@h/p → h, ssh://git@h:p → h). */
    static String hostOf(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isEmpty()) {
            return null;
        }
        String rest = remoteUrl;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int slash = rest.indexOf('/');
        int colon = rest.indexOf(':');
        int end = rest.length();
        if (slash >= 0) {
            end = Math.min(end, slash);
        }
        if (colon >= 0) {
            end = Math.min(end, colon);
        }
        String host = rest.substring(0, end);
        return host.isEmpty() ? null : host;
    }
}
