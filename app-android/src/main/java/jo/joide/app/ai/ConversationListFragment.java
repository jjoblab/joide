package jo.joide.app.ai;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.ai.ConversationStore;
import jo.joide.ai.RelativeTimes;
import jo.joide.app.databinding.ConversationListFragmentBinding;

/**
 * Liste des conversations de l'assistant (Lot 0 de la séparation Assistant /
 * Agent) : écran d'entrée de l'onglet IA de l'accueil. Une carte par
 * conversation passée — titre dérivé du premier message, date relative, compte
 * de messages — plus le CTA « Nouvelle conversation » et un état vide honnête.
 *
 * <p>La persistance vit derrière {@link ConversationStore} (module ai, un
 * fichier JSON par conversation dans {@code filesDir/assistant/}) ; le
 * chargement se fait **hors fil UI** (conception §10) et la liste se
 * rafraîchit à chaque retour d'une conversation ({@link #onResume}).</p>
 */
public final class ConversationListFragment extends Fragment {

    private static final String TAG = "ConversationList";
    private static final String ARG_PROJECT = "project";

    private ConversationListFragmentBinding binding;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Liste des conversations de l'assistant (globales). */
    public static ConversationListFragment assistantList() {
        return new ConversationListFragment();
    }

    /** Liste des conversations de l'agent d'un projet (Lot 4). */
    public static ConversationListFragment forProject(String projectPath) {
        ConversationListFragment fragment = new ConversationListFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    /** Dossier des conversations selon le rôle (assistant global, agent par projet). */
    private java.nio.file.Path storeDir() {
        String project = getArguments() != null
                ? getArguments().getString(ARG_PROJECT, null) : null;
        return requireContext().getFilesDir().toPath().resolve(
                project == null ? "assistant"
                        : "agent/" + ConversationStore.fingerprint(project));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = ConversationListFragmentBinding.inflate(inflater, container, false);

        binding.listBackButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        binding.newConversationButton.setOnClickListener(v -> openConversation(null));

        refresh();
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** Recharge la liste hors fil UI, rend sur le fil UI au retour. */
    private void refresh() {
        final ConversationStore store = new ConversationStore.JsonFiles(
                storeDir(), warning -> Log.w(TAG, "Conversation ignorée : " + warning));
        new Thread(() -> {
            List<ConversationStore.Summary> summaries;
            try {
                summaries = store.list();
            } catch (IOException e) {
                summaries = new ArrayList<>();
                Log.w(TAG, "Lecture des conversations impossible : " + e.getMessage());
            }
            final List<ConversationStore.Summary> loaded = summaries;
            main.post(() -> {
                if (binding != null) {
                    render(loaded);
                }
            });
        }, "assistant-liste").start();
    }

    /** Rend la liste (fil UI) : cartes réelles ou état vide si rien. */
    private void render(List<ConversationStore.Summary> summaries) {
        binding.conversationsList.removeAllViews();
        for (ConversationStore.Summary summary : summaries) {
            binding.conversationsList.addView(conversationCard(summary));
        }
        boolean empty = summaries.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.conversationsList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** Une carte de conversation : titre (début du premier message), méta (date · compte). */
    private View conversationCard(ConversationStore.Summary summary) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dip(10);
        card.setLayoutParams(cardParams);
        card.setPadding(dip(16), dip(14), dip(16), dip(14));
        card.setOnClickListener(v -> openConversation(summary.id()));

        TextView title = new TextView(requireContext());
        title.setText(summary.title() == null || summary.title().isEmpty()
                ? getString(R.string.chat_list_untitled) : summary.title());
        title.setTextColor(getResources().getColor(R.color.sur));
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title);

        TextView meta = new TextView(requireContext());
        meta.setText(getString(R.string.chat_list_message_count, summary.messageCount())
                + " · " + relativeDate(summary.updatedAt()));
        meta.setTextColor(getResources().getColor(R.color.sur_var));
        meta.setTextSize(12.5f);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dip(2);
        meta.setLayoutParams(metaParams);
        card.addView(meta);
        return card;
    }

    /** « à l'instant / il y a N min / N h / hier / N j » depuis l'âge réel. */
    private String relativeDate(long updatedAt) {
        RelativeTimes.Ago ago = RelativeTimes.agoOf(updatedAt, System.currentTimeMillis());
        return switch (ago.unit()) {
            case MINUTES -> ago.value() <= 0
                    ? getString(R.string.time_now)
                    : getString(R.string.time_min_ago, ago.value());
            case HOURS -> getString(R.string.time_hours_ago, ago.value());
            case DAYS -> ago.value() == 1
                    ? getString(R.string.time_yesterday)
                    : String.format(Locale.getDefault(),
                            getString(R.string.time_days_ago), ago.value());
        };
    }

    /** Ouvre une conversation (null : nouvelle) dans le même hôte, selon le rôle. */
    private void openConversation(@Nullable String conversationId) {
        String project = getArguments() != null
                ? getArguments().getString(ARG_PROJECT, null) : null;
        AiPanelFragment panel = project != null
                ? (conversationId == null ? AiPanelFragment.forProject(project)
                        : AiPanelFragment.forProjectWithConversation(project, conversationId))
                : (conversationId == null ? AiPanelFragment.assistant()
                        : AiPanelFragment.assistant(conversationId));
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(), panel)
                .addToBackStack(null)
                .commit();
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
