package jo.joide.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jo.joide.ai.markdown.MarkdownParser;

/**
 * Interprète la réponse brute du modèle (Lot 5) : la transforme en **blocs
 * typés** avant rendu. Pur, testé, streaming-safe (aucune exception sur un
 * préfixe de flux — la propriété est testée sur préfixes).
 *
 * <ul>
 * <li>le raisonnement inline ({@code <think>…</think>},
 * {@code <reasoning>…</reasoning>}) est **retiré** de la réponse et rendu à
 * part (bloc repliable) ;</li>
 * <li>le bloc final {@code ```suivi} devient des propositions (une par
 * ligne), jamais affiché en brut ;</li>
 * <li>les blocs de code avec cible ({@code title="chemin"}) portent leur
 * chemin ; les diffs unifiés ({@code ```diff} ou en-têtes ---/+++/@@) sont
 * détectés comme diffs ; les commandes shell ({@code ```bash}/{@code ```sh})
 * comme commandes ;</li>
 * <li>le nettoyage ferme les fences orphelles, retire les fragments
 * d'appels d'outils ou de JSON fuités, normalise les lignes vides
 * multiples — sans jamais casser un bloc de code.</li>
 * </ul>
 */
public final class ResponseInterpreter {

    private ResponseInterpreter() {}

    /** Un bloc de code interprété, avec sa nature. */
    public record InterpretedCode(MarkdownParser.Block.CodeBlock block, Kind kind,
            String targetPath) {

        /** Nature du bloc de code. */
        public enum Kind { CODE, DIFF, COMMAND, PLAIN }
    }

    /** Réponse interprétée : blocs rendus, raisonnement, propositions. */
    public record Interpreted(String reasoning, List<MarkdownParser.Block> blocks,
            List<InterpretedCode> codeBlocks, List<String> suggestions) {}

    private static final Pattern THINK_TAG =
            Pattern.compile("<(think|reasoning)>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern THINK_OPEN =
            Pattern.compile("<(think|reasoning)>(.*)$", Pattern.DOTALL);
    private static final Pattern SUGGESTION_BLOCK =
            Pattern.compile("```suivi\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern PATH_REFERENCE =
            Pattern.compile("([\\w./-]+\\.[A-Za-z]{1,5}):(\\d+)");

    /**
     * Interprète la réponse (complète ou préfixe de flux). Jamais
     * d'exception ; le résultat final d'un document complet est identique
     * quel que soit le découpage en deltas.
     */
    public static Interpreted interpret(String raw) {
        String document = raw == null ? "" : raw;
        // 1. Raisonnement inline : retiré de la réponse, rendu à part.
        String reasoning = null;
        Matcher closed = THINK_TAG.matcher(document);
        StringBuilder reasoningParts = new StringBuilder();
        while (closed.find()) {
            if (reasoningParts.length() > 0) {
                reasoningParts.append('\n');
            }
            reasoningParts.append(closed.group(2).trim());
        }
        document = closed.reset().replaceAll("");
        Matcher open = THINK_OPEN.matcher(document);
        if (open.find()) {
            if (reasoningParts.length() > 0) {
                reasoningParts.append('\n');
            }
            reasoningParts.append(open.group(2).trim());
            document = document.substring(0, open.start()).trim();
        }
        if (reasoningParts.length() > 0) {
            reasoning = reasoningParts.toString();
        }

        // 2. Bloc de propositions : retiré, une proposition par ligne.
        List<String> suggestions = new ArrayList<>();
        Matcher suivi = SUGGESTION_BLOCK.matcher(document);
        StringBuilder withoutSuivi = new StringBuilder();
        int last = 0;
        while (suivi.find()) {
            withoutSuivi.append(document, last, suivi.start());
            for (String line : suivi.group(1).split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("-")) {
                    suggestions.add(trimmed);
                } else if (trimmed.startsWith("-") && trimmed.length() > 1) {
                    suggestions.add(trimmed.substring(1).trim());
                }
            }
            last = suivi.end();
        }
        withoutSuivi.append(document.substring(last));
        document = withoutSuivi.toString();

        // 3. Nettoyage : fences orphelles fermées, lignes vides multiples,
        //    fragments d'appel d'outil fuités (hors des blocs de code).
        document = cleanOutsideCode(document);

        // 4. Analyse en blocs.
        List<MarkdownParser.Block> blocks = MarkdownParser.parse(document);

        // 5. Nature des blocs de code (code avec cible, diff, commande).
        List<InterpretedCode> codeBlocks = new ArrayList<>();
        for (MarkdownParser.Block block : blocks) {
            if (block instanceof MarkdownParser.Block.CodeBlock code) {
                codeBlocks.add(new InterpretedCode(code, kindOf(code), targetOf(code)));
            }
        }
        return new Interpreted(reasoning, blocks, codeBlocks, suggestions);
    }

    /** Nature d'un bloc de code (diff, commande, code avec cible, texte). */
    private static InterpretedCode.Kind kindOf(MarkdownParser.Block.CodeBlock code) {
        String language = code.language().toLowerCase(java.util.Locale.ROOT);
        if ("diff".equals(language) || looksLikeUnifiedDiff(code.code())) {
            return InterpretedCode.Kind.DIFF;
        }
        if ("bash".equals(language) || "sh".equals(language) || "shell".equals(language)) {
            return InterpretedCode.Kind.COMMAND;
        }
        if (code.title() != null && !code.title().isEmpty()) {
            return InterpretedCode.Kind.CODE;
        }
        return InterpretedCode.Kind.PLAIN;
    }

    /** Vrai si le contenu porte des en-têtes de diff unifié (---/+++/@@). */
    static boolean looksLikeUnifiedDiff(String code) {
        return code != null && (code.contains("@@") || code.contains("--- ")
                && code.contains("+++ "));
    }

    /** Cible d'un bloc : attribut title, ou premier commentaire de chemin. */
    private static String targetOf(MarkdownParser.Block.CodeBlock code) {
        if (code.title() != null && !code.title().isEmpty()) {
            return code.title();
        }
        if (code.code() == null) {
            return null;
        }
        // Premier commentaire de chemin (// app/src/…/Main.java).
        Matcher path = Pattern.compile("^\\s*(?://|#)\\s*(\\S+\\.[A-Za-z]{1,5})",
                Pattern.MULTILINE).matcher(code.code());
        return path.find() ? path.group(1) : null;
    }

    /**
     * Nettoyage hors des blocs de code : ferme une fence orphelle (fin de
     * flux), normalise les lignes vides multiples, retire les fragments
     * d'appels d'outils fuités ({"name":…}) en ligne propre.
     */
    static String cleanOutsideCode(String document) {
        String[] lines = document.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int fenceDepth = 0;
        int blankRun = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            boolean fence = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            if (fence) {
                fenceDepth = fenceDepth == 0 ? 1 : 0;
                out.append(line).append('\n');
                blankRun = 0;
                continue;
            }
            if (fenceDepth > 0) {
                out.append(line).append(i == lines.length - 1 ? "" : '\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                blankRun++;
                if (blankRun <= 1) {
                    out.append('\n');
                }
                continue;
            }
            blankRun = 0;
            // Fragment d'appel d'outil fuité : retiré (le modèle ne devrait
            // jamais montrer son JSON d'outil ; l'UI rend les résultats).
            if (trimmed.startsWith("{\"name\":") || trimmed.startsWith("{\"tool_call")
                    || trimmed.matches("\\{\"chemin\".*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        String cleaned = out.toString();
        // Fence ouverte en fin de flux : fermée proprement (rendu provisoire).
        if (fenceDepth > 0) {
            cleaned += "```";
        }
        return cleaned;
    }
}
