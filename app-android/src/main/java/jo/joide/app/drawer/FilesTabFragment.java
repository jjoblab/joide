package jo.joide.app.drawer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import jo.joide.app.R;
import jo.joide.app.databinding.FilesTabFragmentBinding;

/**
 * Onglet Files du tiroir gauche (Lot 0 : extraction sans régression) :
 * l'arborescence du projet, déplacée **telle quelle** de
 * {@code WorkbenchFragment.setupFilesTree()} — même listage (50 fichiers,
 * tri par profondeur), même rendu (icône fichier, indentation). L'ouverture
 * d'un fichier passe par le contrat {@link WorkbenchHost} : le tiroir ne
 * touche jamais l'éditeur. Les actions Nouveau/Corbeille restent honnêtes
 * (elles se disent, comme avant — le vrai moteur de fichiers arrive au
 * Lot 1).
 *
 * <p>Le chemin du projet vient du {@link WorkspaceViewModel} partagé du
 * tiroir (source unique). Seule retouche au code déplacé : {@code Paths.get}
 * à la place de {@code Path.of} (API 28 sans désucrage — ADR-009).</p>
 */
public final class FilesTabFragment extends Fragment {

    private FilesTabFragmentBinding binding;

    /** Onglet du tiroir (le projet vient du ViewModel partagé). */
    public static FilesTabFragment newInstance() {
        return new FilesTabFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FilesTabFragmentBinding.inflate(inflater, container, false);
        String projectPath = new ViewModelProvider(requireParentFragment())
                .get(WorkspaceViewModel.class).projectPath();
        if (projectPath != null) {
            setupFilesTree(projectPath);
        }
        return binding.getRoot();
    }

    /** Arborescence du projet (déplacée telle quelle de WorkbenchFragment). */
    private void setupFilesTree(String projectPath) {
        binding.filesTree.removeAllViews();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(Paths.get(projectPath))) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(Files::isRegularFile)
                    .limit(50)
                    .forEach(files::add);
        } catch (IOException e) {
            Toast.makeText(requireContext(), getString(
                            R.string.atelier_lecture_impossible,
                            e.getMessage() != null ? e.getMessage() : ""),
                    Toast.LENGTH_SHORT).show();
        }
        files.sort(java.util.Comparator.comparingInt(Path::getNameCount));
        for (Path file : files) {
            binding.filesTree.addView(treeRow(file, projectPath));
        }
        // Actions basses du tiroir (studio : Nouveau + corbeille) — honnêtes
        // tant que le moteur de fichiers (Lot 1) n'existe pas.
        binding.treeNewFile.setOnClickListener(v ->
                Toast.makeText(requireContext(), R.string.atelier_editeur_brique,
                        Toast.LENGTH_SHORT).show());
        binding.treeDelete.setOnClickListener(v ->
                Toast.makeText(requireContext(), R.string.atelier_editeur_brique,
                        Toast.LENGTH_SHORT).show());
    }

    /** Une rangée d'arborescence : indentation par profondeur, dossier/file. */
    private View treeRow(Path file, String projectPath) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int depth = Math.max(0, file.getNameCount()
                - Paths.get(projectPath).getNameCount() - 1);
        row.setPadding(dip(6) + depth * dip(18), dip(6), dip(6), dip(6));

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_file);
        icon.setColorFilter(getResources().getColor(R.color.contour));
        row.addView(icon, new LinearLayout.LayoutParams(dip(16), dip(16)));

        TextView name = new TextView(requireContext());
        name.setText(file.getFileName().toString());
        name.setTextSize(13f);
        name.setTextColor(getResources().getColor(R.color.sur_var));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.leftMargin = dip(8);
        name.setLayoutParams(nameParams);
        row.addView(name);
        row.setOnClickListener(v -> host().openFile(file));
        return row;
    }

    /** L'hôte du tiroir est l'atelier (le parent du tiroir, contrat WorkbenchHost). */
    private WorkbenchHost host() {
        return (WorkbenchHost) requireParentFragment().getParentFragment();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
