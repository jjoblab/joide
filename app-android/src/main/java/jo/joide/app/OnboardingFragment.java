package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import jo.joide.app.databinding.OnboardingFragmentBinding;

/**
 * Onboarding du premier lancement — reconstruction exacte du studio HTML
 * (section « Premier lancement ») : pages scroll centré avec barre segmentée
 * 4 segments 4dp (primaire vs contour_var, opacité 1/.6), zone icône 170dp
 * rayon 26 fond sc (icône 110dp primaire folder/code/spark selon la page),
 * pill k (pc/sur_pc), titre 26sp bold 800 letter -0.4, desc 14sp, pied CTA
 * pleine largeur 64dp (« Continuer » puis « Créer mon premier projet ») et
 * lien primaire 13sp (« Page précédente » / « Passer pour l'instant »).
 *
 * <p>Quatre pages exactes du studio. La dernière action mène au flux réel :
 * l'assistant du choix du mode ({@link ModeSetupFragment}), qui déroute vers
 * l'installation. Le passage (« Passer pour l'instant ») fait de même :
 * aucun défaut caché (ADR-002), le choix du mode reste requis.</p>
 */
public class OnboardingFragment extends Fragment {

    /** Les 4 pages du studio (kicker, titre, description, icône). */
    private record Page(int kicker, int title, int description, int icon) { }

    private static final Page[] PAGES = {
            new Page(R.string.onboarding_page1_kicker, R.string.onboarding_page1_title,
                    R.string.onboarding_page1_desc, R.drawable.ic_folder),
            new Page(R.string.onboarding_page2_kicker, R.string.onboarding_page2_title,
                    R.string.onboarding_page2_desc, R.drawable.ic_folder),
            new Page(R.string.onboarding_page3_kicker, R.string.onboarding_page3_title,
                    R.string.onboarding_page3_desc, R.drawable.ic_code),
            new Page(R.string.onboarding_page4_kicker, R.string.onboarding_page4_title,
                    R.string.onboarding_page4_desc, R.drawable.ic_spark),
    };

    private OnboardingFragmentBinding binding;
    private int index;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = OnboardingFragmentBinding.inflate(inflater, container, false);

        binding.onboardingCta.setOnClickListener(v -> {
            if (index < PAGES.length - 1) {
                show(index + 1);
            } else {
                enterApp();
            }
        });
        binding.onboardingLink.setOnClickListener(v -> {
            if (index > 0) {
                show(index - 1);
            } else {
                enterApp();
            }
        });

        show(0);
        return binding.getRoot();
    }

    /** Affiche la page i : segments, icône, pill, titre, description, pied. */
    private void show(int i) {
        index = i;
        Page page = PAGES[i];
        View[] segments = {binding.onboardingSegment1, binding.onboardingSegment2,
                binding.onboardingSegment3, binding.onboardingSegment4};

        // Segments : primaire (opacité 1) pour k <= i, contour_var (.6) sinon.
        for (int k = 0; k < segments.length; k++) {
            View segment = segments[k];
            boolean done = k <= i;
            segment.setBackgroundColor(ContextCompat.getColor(requireContext(),
                    done ? R.color.primaire : R.color.contour_var));
            segment.setAlpha(done ? 1f : 0.6f);
        }

        binding.onboardingIcon.setImageResource(page.icon());
        binding.onboardingKicker.setText(getString(page.kicker()));
        binding.onboardingTitle.setText(getString(page.title()));
        binding.onboardingDesc.setText(getString(page.description()));

        boolean last = i == PAGES.length - 1;
        binding.onboardingCtaText.setText(getString(last
                ? R.string.onboarding_cta_create : R.string.onboarding_cta_next));
        binding.onboardingLink.setText(getString(i > 0
                ? R.string.onboarding_link_previous : R.string.onboarding_link_skip));
    }

    /** La dernière action mène au flux réel : choix du mode (ADR-002). */
    private void enterApp() {
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(),
                        new ModeSetupFragment())
                .commit();
    }
}
