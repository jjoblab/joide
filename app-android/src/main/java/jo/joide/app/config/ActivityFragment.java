package jo.joide.app.config;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.ActivityFragmentBinding;
import jo.joide.corestore.ActivityLog;
import jo.joide.corestore.CrashJournal;

/**
 * Journal d'activité (lot L10, d'après BuildActivity + DaemonCrashNote + la
 * visionneuse de la référence) : la chronologie FUSIONNÉE, la plus récente en
 * tête — builds de l'atelier et verdicts ({@link ActivityLog}), passes
 * d'indexation, écroulements des trois zones ({@link CrashJournal} : UI,
 * moteur, terminal). Le tap sur une rangée portant un détail (trace,
 * statistiques) le DÉPLIE. Une erreur dépliée s'ouvre dans
 * {@link ErrorSheet} (feuille, jamais un dialogue).
 */
public class ActivityFragment extends Fragment {

    /** Une ligne de la chronologie fusionnée (source : activité ou crash). */
    private record Ligne(long epochMs, int icône, int couleur, String message,
            String détail) {}

    private ActivityFragmentBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ActivityFragmentBinding.inflate(inflater, container, false);
        binding.activityBack.setOnClickListener(v ->
                getParentFragmentManager().popBackStack());
        rendreChronologie();
        return binding.getRoot();
    }

    private void rendreChronologie() {
        List<Ligne> lignes = fusionner();
        binding.activityList.removeAllViews();
        if (lignes.isEmpty()) {
            TextView vide = new TextView(requireContext());
            vide.setText(R.string.activite_vide);
            vide.setTextColor(ContextCompat.getColor(requireContext(), R.color.sur_var));
            vide.setTextSize(12.5f);
            vide.setPadding(dip(8), dip(24), dip(8), dip(8));
            binding.activityList.addView(vide);
            return;
        }
        for (Ligne ligne : lignes) {
            binding.activityList.addView(rangée(ligne));
        }
    }

    /** Fusionne activité + écroulements, la plus récente en tête. */
    private List<Ligne> fusionner() {
        ActivityLog activité = new ActivityLog(requireContext().getFilesDir()
                .toPath().resolve("journal/activite.txt"));
        CrashJournal crashes = new CrashJournal(requireContext().getFilesDir()
                .toPath().resolve("journal/ecroulements.txt"));

        List<Ligne> lignes = new ArrayList<>();
        for (ActivityLog.Entry entry : activité.read()) {
            int icône;
            int couleur;
            switch (entry.kind()) {
                case BUILD -> {
                    icône = R.drawable.ic_play;
                    couleur = R.color.sur_var;
                }
                case VERDICT -> {
                    icône = R.drawable.ic_play;
                    couleur = entry.message().contains("échou") || entry.message().contains("écroul")
                            ? R.color.erreur : R.color.ok;
                }
                case INDEX -> {
                    icône = R.drawable.ic_search;
                    couleur = R.color.alerte;
                }
                case CRASH -> {
                    icône = R.drawable.ic_dots;
                    couleur = R.color.erreur;
                }
                default -> {
                    icône = R.drawable.ic_dots;
                    couleur = R.color.sur_var;
                }
            }
            lignes.add(new Ligne(entry.epochMs(), icône, couleur,
                    entry.message(), entry.detail()));
        }
        for (CrashJournal.Entry entry : crashes.read()) {
            int couleur = switch (entry.zone()) {
                case MOTEUR -> R.color.erreur;
                case TERMINAL -> R.color.alerte;
                case UI -> R.color.sur_var;
            };
            String message = getString(R.string.activite_crash_zone,
                    entry.zone().name(), entry.errorClass());
            lignes.add(new Ligne(entry.epochMs(), R.drawable.ic_dots,
                    couleur, message, entry.message()));
        }
        lignes.sort(Comparator.comparingLong(Ligne::epochMs).reversed());
        return lignes;
    }

    /** Une rangée : icône + message + âge ; tap = dépliage du détail. */
    private View rangée(Ligne ligne) {
        LinearLayout carte = new LinearLayout(requireContext());
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams paramsCarte = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsCarte.topMargin = dip(8);
        carte.setLayoutParams(paramsCarte);

        LinearLayout tête = new LinearLayout(requireContext());
        tête.setOrientation(LinearLayout.HORIZONTAL);
        tête.setGravity(Gravity.CENTER_VERTICAL);
        tête.setPadding(dip(12), dip(11), dip(12), dip(11));

        ImageView icône = new ImageView(requireContext());
        icône.setImageResource(ligne.icône());
        icône.setColorFilter(ContextCompat.getColor(requireContext(), ligne.couleur()));
        tête.addView(icône, new LinearLayout.LayoutParams(dip(22), dip(22)));

        TextView message = new TextView(requireContext());
        message.setText(ligne.message());
        message.setTextColor(getResources().getColor(R.color.sur));
        message.setTextSize(13f);
        LinearLayout.LayoutParams paramsMessage = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        paramsMessage.leftMargin = dip(12);
        message.setLayoutParams(paramsMessage);
        message.setSingleLine(true);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tête.addView(message);

        TextView âge = new TextView(requireContext());
        âge.setText(relatif(Instant.ofEpochMilli(ligne.epochMs())));
        âge.setTextColor(getResources().getColor(R.color.sur_var));
        âge.setTextSize(11.5f);
        tête.addView(âge);

        carte.addView(tête);

        if (ligne.détail() != null && !ligne.détail().isBlank()) {
            TextView détail = new TextView(requireContext());
            détail.setText(ligne.détail());
            détail.setTextColor(getResources().getColor(R.color.sur_var));
            détail.setTextSize(11.5f);
            détail.setTypeface(android.graphics.Typeface.MONOSPACE);
            détail.setPadding(dip(12), dip(4), dip(12), dip(11));
            détail.setVisibility(View.GONE);
            tête.setOnClickListener(v ->
                    détail.setVisibility(détail.getVisibility() == View.VISIBLE
                            ? View.GONE : View.VISIBLE));
            carte.addView(détail);
        }
        return carte;
    }

    /** « il y a 2 min » — âge relatif court. */
    private static String relatif(Instant quand) {
        long minutes = Duration.between(quand, Instant.now()).toMinutes();
        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%d min", Math.max(1, minutes));
        }
        if (minutes < 24 * 60) {
            return String.format(Locale.getDefault(), "%d h", minutes / 60);
        }
        return String.format(Locale.getDefault(), "%d j", minutes / (24 * 60));
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
