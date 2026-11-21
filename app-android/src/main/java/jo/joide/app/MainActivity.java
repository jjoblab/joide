package jo.joide.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import jo.joide.app.databinding.MainActivityBinding;
import jo.joide.corestore.CrashJournal;

/**
 * Activité unique : hôte des fragments (accueil, atelier, paramètres). Le choix du mode
 * au premier lancement déroute vers l'assistant (ADR-002) tant qu'aucun mode n'est choisi.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Journal des écroulements (conception §10) : le gestionnaire par défaut
        // capture les plantages UI dans le journal borné, PUIS chaîne vers le
        // gestionnaire précédent (la mort est enregistrée comme avant — l'esprit
        // de la note de plantage de la référence, côté UI).
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        final CrashJournal journal = new CrashJournal(
                getFilesDir().toPath().resolve("journal/ecroulements.txt"));
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            journal.record(CrashJournal.Zone.UI, error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
        MainActivityBinding binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (savedInstanceState == null) {
            Fragment initial = ModeSetupFragment.isRequired(this)
                    ? new ModeSetupFragment()
                    : new HomeFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(binding.container.getId(), initial)
                    .commit();
        }
    }
}
