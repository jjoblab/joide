package jo.joide.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import jo.joide.app.databinding.MainActivityBinding;

/**
 * Activité unique : hôte des fragments (accueil, atelier, paramètres). Le choix du mode
 * au premier lancement déroute vers l'assistant (ADR-002) tant qu'aucun mode n'est choisi.
 *
 * <p>Le journal applicatif et le gestionnaire de plantages vivent désormais dans
 * {@link jo.joide.app.diag.DiagApp} (ADR-021) : installés en première ligne du
 * démarrage du PROCESSUS — avant la première activité, avant tout écran.</p>
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivityBinding binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // Réparation au démarrage (Forge v0.2.24) : une installation du moteur
        // interrompue — moteur mis à l'écart par une réinstallation tuée entre
        // deux renommages — est rétablie AVANT tout écran, sans action de
        // l'utilisateur (l'ancien moteur reprend sa place).
        new jo.joide.buildforge.ForgeInstaller(
                getFilesDir().toPath().resolve("forge")).recoverInterruptedInstall();
        // Lot 2 : migration silencieuse des identifiants de fournisseur
        // historiques (« passerelle » → « custom ») avant tout écran IA.
        jo.joide.app.settings.SettingsIaPageFragment.migrateProviders(this);
        // Lot 7 : le mode de la porte unique se restaure au démarrage
        // (persisté — avant il vivait en mémoire, perdu à chaque lancement).
        android.content.SharedPreferences iaPrefs = getSharedPreferences(
                jo.joide.app.settings.SettingsIaPageFragment.IA_PREFS, MODE_PRIVATE);
        jo.joide.app.settings.SettingsFragment.sharedGate().setMode(
                jo.joide.app.settings.SettingsIaHubFragment.modeOf(iaPrefs));
        if (savedInstanceState == null) {
            // Premier lancement (studio HTML) : onboarding (4 pages) → assistant
            // du choix du mode → installation. L'onboarding ne remplace jamais
            // le choix du mode (ADR-002 : aucun défaut caché).
            Fragment initial = ModeSetupFragment.isRequired(this)
                    ? new OnboardingFragment()
                    : new HomeFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(binding.container.getId(), initial)
                    .commit();
        }
    }
}
