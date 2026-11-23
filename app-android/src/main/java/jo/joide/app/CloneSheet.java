package jo.joide.app;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.nio.file.Files;
import java.nio.file.Path;
import jo.joide.app.R;
import jo.joide.app.databinding.CloneSheetBinding;
import jo.joide.app.drawer.GitCredentialsStore;
import jo.joide.coregit.GitOps;
import jo.joide.coregit.GitService;
import jo.joide.coremodel.ProjectDetector;

/**
 * Feuille de clonage (lot L10, demande du propriétaire : « une option depuis
 * l'écran home pour cloner un dépôt git ») — un bottom sheet, jamais un
 * dialogue, même design que le reste de la maison.
 *
 * <p>Parcours de la référence, adapté : URL + dossier de destination
 * (prérempli du dernier segment, dérivation auto tant que non édité),
 * progression réelle du clonage JGit (fraction + tâche), identifiants par
 * hôte du coffre si le dépôt est privé, **nettoyage du demi-dépôt en cas
 * d'échec** (la tentative suivante repart propre), puis verdict honnête :
 * projet reconnu (forge.json / Gradle → ouvrable) ou dossier éditable —
 * jamais de faux « projet » pour un dépôt sans système de build.</p>
 */
public class CloneSheet extends BottomSheetDialogFragment {

    private CloneSheetBinding binding;

    /** Le nom de dossier a-t-il été édité à la main ? (sinon dérivation auto.) */
    private boolean dossierÉdité;

    /** Clonage en cours : les champs se verrouillent. */
    private boolean enCours;

    /** Hôte du fragment appelant (accueil : rafraîchit la liste, ouvre l'atelier). */
    public interface Host {

        /** Le clone est fini — l'accueil rafraîchit sa liste ({@code reconnu} :
         *  un système de build vit dans le dossier cloné). */
        void onCloned(Path target, boolean reconnu);

        /** L'utilisateur a demandé l'ouverture du dossier cloné. */
        void onOpenRequested(Path target);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = CloneSheetBinding.inflate(inflater, container, false);

        binding.cloneUrlField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                dériverDossier();
                rafraîchirDestination();
            }
        });
        binding.cloneDossierField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                dossierÉdité = true;
                rafraîchirDestination();
            }
        });
        rafraîchirDestination();

        binding.cloneBouton.setOnClickListener(v -> lancerClonage());
        binding.cloneAnnulerBouton.setOnClickListener(v -> {
            if (!enCours) {
                dismiss();
            }
        });
        binding.cloneOuvrirBouton.setOnClickListener(v -> {
            Path cible = cible();
            Fragment parent = getParentFragment();
            if (cible != null && parent instanceof Host host) {
                host.onOpenRequested(cible);
            }
            dismiss();
        });
        binding.cloneFermerBouton.setOnClickListener(v -> dismiss());
        return binding.getRoot();
    }

    /** Dossier cible (filesDir/projects/<nom>) — null si le nom est vide. */
    @Nullable
    private Path cible() {
        String nom = nomDossier();
        if (nom == null) {
            return null;
        }
        return HomeFragment.projectsRoot(requireContext()).resolve(nom);
    }

    /** Nom saisi, nettoyé ; null si vide. */
    @Nullable
    private String nomDossier() {
        String nom = binding.cloneDossierField.getText().toString().trim();
        return nom.isEmpty() ? null : nom;
    }

    /**
     * Dérivation auto du nom : dernier segment de l'URL, {@code .git} retiré —
     * tant que l'utilisateur n'a pas édité le champ (même mécanique que le
     * paquet de la création).
     */
    private void dériverDossier() {
        if (dossierÉdité || enCours) {
            return;
        }
        String dérivé = dernierSegment(
                binding.cloneUrlField.getText().toString());
        if (dérivé != null && !dérivé.isEmpty()) {
            binding.cloneDossierField.setText(dérivé);
            dossierÉdité = false; // la dérivation n'est pas une édition.
        }
    }

    /** « projects/<nom> » sous le stockage réel ; le chemin complet sinon. */
    private void rafraîchirDestination() {
        Path cible = cible();
        binding.cloneDestination.setText(getString(R.string.clone_destination,
                cible != null ? "projects/" + cible.getFileName()
                        : getString(R.string.clone_destination_inconnue)));
    }

    /** Le clonage : verrouille la feuille, progression, verdict. */
    private void lancerClonage() {
        if (enCours) {
            return;
        }
        String url = binding.cloneUrlField.getText().toString().trim();
        if (url.isEmpty()) {
            toast(getString(R.string.clone_url_vide));
            return;
        }
        Path cible = cible();
        if (cible == null) {
            toast(getString(R.string.clone_url_vide));
            return;
        }
        if (Files.exists(cible)) {
            toast(getString(R.string.clone_dossier_occupe,
                    cible.getFileName().toString()));
            return;
        }
        enCours = true;
        binding.cloneUrlField.setEnabled(false);
        binding.cloneDossierField.setEnabled(false);
        binding.cloneBouton.setAlpha(0.5f);
        binding.cloneBouton.setEnabled(false);
        binding.cloneResultat.setVisibility(View.GONE);
        binding.cloneOuvrirZone.setVisibility(View.GONE);
        binding.cloneProgressZone.setVisibility(View.VISIBLE);
        binding.cloneProgressTask.setText(getString(R.string.clone_en_cours));

        // Identifiants du coffre par hôte (dépôt privé) — anonyme sinon.
        GitOps.Credentials identifiants =
                new GitCredentialsStore(requireContext()).of(url);
        GitService.CloneProgress progression = (fraction, tâche) ->
                binding.cloneProgressZone.post(() -> {
                    if (binding == null) {
                        return;
                    }
                    ProgressBar barre = binding.cloneProgressBar;
                    if (fraction >= 0) {
                        barre.setIndeterminate(false);
                        barre.setMax(100);
                        barre.setProgress((int) Math.round(fraction * 100), true);
                    } else {
                        barre.setIndeterminate(true);
                    }
                    binding.cloneProgressTask.setText(getString(
                            R.string.clone_tache,
                            String.valueOf((int) Math.max(0, fraction * 100)),
                            tâche == null ? "" : tâche));
                });

        new Thread(() -> {
            Path cloné = null;
            Exception échec = null;
            try {
                cloné = GitService.clone(url, cible, identifiants, progression, 60);
            } catch (Exception e) {
                échec = e;
            }
            Path résultat = cloné;
            Exception erreur = échec;
            binding.cloneProgressZone.post(() -> rendreVerdict(résultat, erreur));
        }, "clone-git").start();
    }

    /** Le verdict, sur le fil UI : succès (reconnu ou non) ou échec. */
    private void rendreVerdict(@Nullable Path cloné, @Nullable Exception erreur) {
        if (binding == null) {
            return;
        }
        enCours = false;
        binding.cloneUrlField.setEnabled(true);
        binding.cloneDossierField.setEnabled(true);
        binding.cloneBouton.setAlpha(1f);
        binding.cloneBouton.setEnabled(true);
        binding.cloneProgressZone.setVisibility(View.GONE);
        if (erreur != null) {
            // Le dossier partiel a été nettoyé par GitService : la feuille
            // reste ouverte, l'utilisateur corrige et recommence.
            verdict(getString(R.string.clone_echec,
                    erreur.getMessage() == null ? "" : erreur.getMessage()),
                    false, R.color.sur_conteneur_erreur,
                    R.drawable.fond_carte_erreur, null);
            return;
        }
        boolean reconnu = !ProjectDetector.compatibleModes(cloné).isEmpty();
        if (reconnu) {
            verdict(getString(R.string.clone_ok_reconnu,
                    cloné.getFileName().toString()), true,
                    R.color.ok, R.drawable.fond_carte,
                    getString(R.string.clone_ouvrir));
        } else {
            verdict(getString(R.string.clone_ok_inconnu,
                    cloné.getFileName().toString()), true,
                    R.color.alerte, R.drawable.fond_carte_alerte,
                    getString(R.string.clone_ouvrir_quand_meme));
        }
        // L'accueil rafraîchit sa liste tout de suite (le projet reconnu
        // apparaît ; l'inconnu y reste invisible — le verdict le dit).
        if (getParentFragment() instanceof Host host) {
            host.onCloned(cloné, reconnu);
        }
    }

    /** Affiche le verdict + la rangée d'ouverture (succès) ou l'échec seul. */
    private void verdict(String message, boolean succès, int couleur, int fond,
            @Nullable String libelléOuvrir) {
        binding.cloneResultat.setText(message);
        binding.cloneResultat.setTextColor(
                ContextCompat.getColor(requireContext(), couleur));
        binding.cloneResultat.setBackground(
                ContextCompat.getDrawable(requireContext(), fond));
        binding.cloneResultat.setVisibility(View.VISIBLE);
        binding.cloneOuvrirZone.setVisibility(succès ? View.VISIBLE : View.GONE);
        if (libelléOuvrir != null) {
            binding.cloneOuvrirText.setText(libelléOuvrir);
        }
    }

    /** Dernier segment d'une URL de dépôt (« .git » retiré). */
    static String dernierSegment(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String segment = url.trim();
        int slash = segment.lastIndexOf('/');
        if (slash >= 0) {
            segment = segment.substring(slash + 1);
        }
        if (segment.endsWith(".git")) {
            segment = segment.substring(0, segment.length() - 4);
        }
        return segment.isBlank() ? null : segment;
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
