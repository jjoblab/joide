package jo.joide.app.diag;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Dernier écran connu avant un plantage (portage CodeIDE §5.8, ADR-021) :
 * le nom simple de l'activité résidente. S'enregistre lui-même comme
 * écouteur du cycle de vie à l'installation — aucune configuration
 * supplémentaire. La valeur est volatile — lue depuis n'importe quel fil au
 * moment du plantage, sans verrou.
 */
public final class LastScreenTracker implements Application.ActivityLifecycleCallbacks {

    private volatile String lastScreen;

    /** Dernier écran connu, ou {@code null} avant tout affichage. */
    public String current() {
        return lastScreen;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        lastScreen = activity.getClass().getSimpleName();
    }

    // Seul le résumé compte : géré par onActivityResumed.

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // L'écran reste « le dernier connu » — c'est le but.
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
