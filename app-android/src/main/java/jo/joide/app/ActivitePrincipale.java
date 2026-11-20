package jo.joide.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import jo.joide.app.databinding.ActivitePrincipaleBinding;

/**
 * Activité unique : hôte des fragments (accueil, atelier, paramètres). Le choix du mode
 * au premier lancement déroute vers l'assistant (ADR-002) tant qu'aucun mode n'est choisi.
 */
public class ActivitePrincipale extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        ActivitePrincipaleBinding vue = ActivitePrincipaleBinding.inflate(getLayoutInflater());
        setContentView(vue.getRoot());
        if (savedInstanceState == null) {
            Fragment initial = FragmentAssistantModes.nouveauSiPremierLancement(this)
                    ? new FragmentAssistantModes()
                    : new FragmentAccueil();
            getSupportFragmentManager().beginTransaction()
                    .replace(vue.conteneur.getId(), initial)
                    .commit();
        }
    }
}
