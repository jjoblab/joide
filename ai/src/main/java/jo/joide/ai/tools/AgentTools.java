package jo.joide.ai.tools;

import java.util.List;
import jo.joide.ai.AgentLoop;
import jo.joide.ai.AgentWorkspace;

/**
 * Outils de l'agent (Lot 3) : fabrique du roster par défaut — lectures
 * (fichier, arborescence, recherche, problèmes, fichier ouvert, sélection),
 * mutations (remplacement ciblé, écriture, création, renommage, suppression),
 * build via le port du mode actif. Les outils sans valeur réelle de l'hôte le
 * disent au modèle (jamais de valeur inventée).
 */
public final class AgentTools {

    private AgentTools() {}

    /** Roster complet de l'agent du projet (l'hôte fournit ses services vivants). */
    public static java.util.List<AgentTool> defaultRoster(AgentTool.ToolHost host,
            jo.joide.ai.BuildRunner buildRunner) {
        java.util.List<AgentTool> roster = new java.util.ArrayList<>(java.util.List.of(
                new ReadFileTool(),
                new ListTreeTool(),
                new SearchTextTool(),
                new ReadProblemsTool(host),
                new ReadOpenFileTool(host),
                new ReadSelectionTool(host),
                new ModifyFileTool(),
                new WriteFileTool(),
                new CreateFileTool(),
                new RenameFileTool(),
                new DeleteFileTool()));
        if (buildRunner != null) {
            roster.add(new BuildTool(buildRunner));
        }
        return roster;
    }

    /** Roster de l'assistant général : VIDE (aucun outil, par construction). */
    public static java.util.List<AgentTool> assistantRoster() {
        return java.util.Collections.emptyList();
    }

    /** Nom de fichier court pour les résumés d'outil (transparence). */
    static String shortName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** Bornage d'un résumé de résultat. */
    static String bounded(String summary, int max) {
        return summary.length() <= max ? summary : summary.substring(0, max) + "…";
    }

    // ---- Outil de lecture de fichier ------------------------------------------

    /** Lit un fichier dans une fenêtre de lignes. */
    static final class ReadFileTool implements AgentTool {
        @Override public String name() { return "lire_fichier"; }
        @Override public String description() {
            return "Lit un fichier du projet dans une fenêtre de lignes numérotées "
                    + "(lecture seule). Commence par ligne_debut=1, nb_lignes=200.";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"chemin\":{\"type\":\"string\","
                    + "\"description\":\"chemin relatif à la racine du projet\"},"
                    + "\"ligne_debut\":{\"type\":\"integer\",\"description\":\"première ligne (défaut 1)\"},"
                    + "\"nb_lignes\":{\"type\":\"integer\",\"description\":\"nombre de lignes (défaut 200, max 400)\"}},"
                    + "\"required\":[\"chemin\"]}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String path = args.string("chemin");
            int first = Math.max(1, args.intOr("ligne_debut", 1));
            int lines = Math.min(400, Math.max(1, args.intOr("nb_lignes", 200)));
            AgentWorkspace.Bounded bounded = w.readFile(path, first, lines);
            return bounded.omitted() > 0
                    ? bounded.content() + "\n(" + bounded.omitted()
                            + " lignes non affichées — déplace la fenêtre)"
                    : bounded.content();
        }
    }

    // ---- Outil d'arborescence ---------------------------------------------------

    /** Liste l'arborescence (dossiers générés ignorés, profondeur bornée). */
    static final class ListTreeTool implements AgentTool {
        @Override public String name() { return "lister_arborescence"; }
        @Override public String description() {
            return "Liste l'arborescence d'un dossier (dossiers puis fichiers, "
                    + "dossiers générés et .git ignorés, profondeur bornée — lecture seule).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"chemin\":{\"type\":\"string\","
                    + "\"description\":\"dossier relatif (« . » pour la racine)\"},"
                    + "\"profondeur\":{\"type\":\"integer\",\"description\":\"profondeur max (défaut 4, max 8)\"}},"
                    + "\"required\":[\"chemin\"]}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String path = args.stringOr("chemin", ".");
            int depth = Math.min(8, Math.max(1, args.intOr("profondeur", 4)));
            return w.listTree(path, depth, 200).content();
        }
    }

    // ---- Outil de recherche -----------------------------------------------------

    /** Vraie recherche de texte (regex, casse, mot entier, glob). */
    static final class SearchTextTool implements AgentTool {
        @Override public String name() { return "chercher_texte"; }
        @Override public String description() {
            return "Cherche un texte dans les fichiers du projet (lecture seule). "
                    + "Résultats « chemin:ligne: extrait » ; options : expression "
                    + "régulière, casse, mot entier, filtre de fichiers (ex. *.java).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"requete\":{\"type\":\"string\","
                    + "\"description\":\"texte cherché (ou expression régulière si regex=true)\"},"
                    + "regex\":{\"type\":\"boolean\",\"description\":\"traiter la requête comme une regex (défaut faux)\"},"
                    + "respecter_casse\":{\"type\":\"boolean\",\"description\":\"sensible à la casse (défaut faux)\"},"
                    + "mot_entier\":{\"type\":\"boolean\",\"description\":\"mot entier seulement (défaut faux)\"},"
                    + "filtre\":{\"type\":\"string\",\"description\":\"glob de fichiers, ex. *.java (défaut : tout)\"}},"
                    + "\"required\":[\"requete\"]}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String query = args.string("requete");
            jo.joide.ai.SearchOptions options = new jo.joide.ai.SearchOptions(
                    args.boolOr("regex", false), args.boolOr("respecter_casse", false),
                    args.boolOr("mot_entier", false), args.stringOr("filtre", null), 0);
            return w.search(query, options, 30).content();
        }
    }

    // ---- Outils sur l'état vivant de l'hôte -------------------------------------

    /** Diagnostics réels du dernier build. */
    static final class ReadProblemsTool implements AgentTool {
        private final ToolHost host;
        ReadProblemsTool(ToolHost host) { this.host = host; }
        @Override public String name() { return "lire_problemes"; }
        @Override public String description() {
            return "Liste les diagnostics réels du dernier build de l'atelier "
                    + "(« fichier:ligne: message » — lecture seule).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) {
            java.util.List<String> problems = host == null ? null : host.problemLines();
            if (problems == null) {
                return "Aucun diagnostic disponible : aucun build n'a encore tourné "
                        + "dans l'atelier. Utilise construire_module pour en lancer un.";
            }
            if (problems.isEmpty()) {
                return "Aucun problème : le dernier build est propre.";
            }
            return problems.size() + " problèmes :\n" + String.join("\n",
                    problems.subList(0, Math.min(30, problems.size())));
        }
    }

    /** Tampon vivant du fichier ouvert dans l'éditeur. */
    static final class ReadOpenFileTool implements AgentTool {
        private final ToolHost host;
        ReadOpenFileTool(ToolHost host) { this.host = host; }
        @Override public String name() { return "lire_fichier_ouvert"; }
        @Override public String description() {
            return "Rend le contenu du fichier ouvert dans l'éditeur (tampon vivant, "
                    + "modifications non enregistrées comprises — lecture seule).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) {
            String content = host == null ? null : host.openFileContent();
            if (content == null) {
                return "Aucun fichier n'est ouvert dans l'éditeur.";
            }
            String name = host.openFileName() == null ? "sans nom" : host.openFileName();
            return "Fichier ouvert : " + name + "\n" + bounded(content, 8_000);
        }
    }

    /** Sélection courante de l'éditeur. */
    static final class ReadSelectionTool implements AgentTool {
        private final ToolHost host;
        ReadSelectionTool(ToolHost host) { this.host = host; }
        @Override public String name() { return "lire_selection"; }
        @Override public String description() {
            return "Rend la sélection courante de l'éditeur (ce sur quoi travaille "
                    + "l'utilisateur — lecture seule).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) {
            String selection = host == null ? null : host.selectionText();
            if (selection == null || selection.isEmpty()) {
                return "Aucune sélection dans l'éditeur.";
            }
            return "Sélection :\n" + bounded(selection, 4_000);
        }
    }

    // ---- Outils de mutation ------------------------------------------------------

    /** Remplacement ciblé — l'outil de modification PAR DÉFAUT. */
    static final class ModifyFileTool implements AgentTool {
        @Override public String name() { return "modifier_fichier"; }
        @Override public String description() {
            return "Modifie un fichier par remplacement ciblé : ancien_texte (exact, "
                    + "espaces compris) devient nouveau_texte. Échoue si introuvable "
                    + "ou ambigu — c'est l'outil de modification à privilégier "
                    + "(mutation, permission requise).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"chemin\":{\"type\":\"string\","
                    + "\"description\":\"chemin relatif du fichier\"},"
                    + "ancien_texte\":{\"type\":\"string\",\"description\":\"texte exact à remplacer (copie-le du fichier)\"},"
                    + "nouveau_texte\":{\"type\":\"string\",\"description\":\"texte de remplacement\"}},"
                    + "\"required\":[\"chemin\",\"ancien_texte\",\"nouveau_texte\"]}";
        }
        @Override public boolean readOnly() { return false; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String path = args.string("chemin");
            long size = w.applyEdit(path, args.string("ancien_texte"),
                    args.string("nouveau_texte"));
            return "Modifié : " + path + " (" + size + " caractères au total)";
        }
    }

    /** Réécriture complète d'un fichier. */
    static final class WriteFileTool implements AgentTool {
        @Override public String name() { return "ecrire_fichier"; }
        @Override public String description() {
            return "Réécrit ENTIEREMENT un fichier (mutation, permission requise). "
                    + "Préfère modifier_fichier pour un changement ciblé.";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"chemin\":{\"type\":\"string\","
                    + "\"description\":\"chemin relatif du fichier\"},"
                    + "contenu\":{\"type\":\"string\",\"description\":\"contenu complet du fichier\"}},"
                    + "\"required\":[\"chemin\",\"contenu\"]}";
        }
        @Override public boolean readOnly() { return false; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String path = args.string("chemin");
            long written = w.writeFile(path, args.string("contenu"));
            return "Écrit : " + path + " (" + written + " caractères)";
        }
    }

    /** Création d'un fichier nouveau. */
    static final class CreateFileTool implements AgentTool {
        @Override public String name() { return "creer_fichier"; }
        @Override public String description() {
            return "Crée un fichier nouveau (échoue s'il existe — mutation, permission requise).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"chemin\":{\"type\":\"string\","
                    + "\"description\":\"chemin relatif du fichier à créer\"},"
                    + "contenu\":{\"type\":\"string\",\"description\":\"contenu initial\"}},"
                    + "\"required\":[\"chemin\",\"contenu\"]}";
        }
        @Override public boolean readOnly() { return false; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String path = args.string("chemin");
            return w.createFile(path, args.stringOr("contenu", ""))
                    ? "Créé : " + path : "Échec : " + path + " existe déjà";
        }
    }

    /** Renommage/déplacement. */
    static final class RenameFileTool implements AgentTool {
        @Override public String name() { return "renommer_fichier"; }
        @Override public String description() {
            return "Renomme ou déplace un fichier (mutation, permission requise).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"chemin\":{\"type\":\"string\","
                    + "\"description\":\"chemin relatif actuel\"},"
                    + "nouveau_chemin\":{\"type\":\"string\",\"description\":\"chemin relatif cible\"}},"
                    + "\"required\":[\"chemin\",\"nouveau_chemin\"]}";
        }
        @Override public boolean readOnly() { return false; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String from = args.string("chemin");
            String to = args.string("nouveau_chemin");
            return w.renameFile(from, to) ? "Renommé : " + from + " → " + to
                    : "Échec : " + from + " introuvable ou " + to + " existe déjà";
        }
    }

    /** Suppression (irréversible — signalée). */
    static final class DeleteFileTool implements AgentTool {
        @Override public String name() { return "supprimer_fichier"; }
        @Override public String description() {
            return "Supprime un fichier (IRRÉVERSIBLE — mutation, permission requise).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"chemin\":{\"type\":\"string\","
                    + "\"description\":\"chemin relatif du fichier à supprimer\"}},"
                    + "\"required\":[\"chemin\"]}";
        }
        @Override public boolean readOnly() { return false; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            String path = args.string("chemin");
            return w.deleteFile(path) ? "Supprimé : " + path
                    : "Introuvable : " + path;
        }
    }

    // ---- Outil de build ------------------------------------------------------------

    /** Build via le moteur du mode actif (jamais un moteur concurrent). */
    static final class BuildTool implements AgentTool {
        private final jo.joide.ai.BuildRunner runner;
        BuildTool(jo.joide.ai.BuildRunner runner) { this.runner = runner; }
        @Override public String name() { return "construire_module"; }
        @Override public String description() {
            return "Compile le module du projet via le moteur du mode actif de "
                    + "l'atelier (retourne le journal borné).";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            jo.joide.ai.BuildRunner.BuildOutcome outcome = runner.build(null);
            return (outcome.success() ? "BUILD REUSSI" : "BUILD ECHOUE")
                    + " (" + outcome.eventCount() + " événements)\n" + outcome.tailLog();
        }
    }

    // ---- Outils d'interaction ---------------------------------------------------------

    /**
     * Questions à l'utilisateur (Lot 3) : suspend le tour comme une
     * permission — la réponse de l'utilisateur devient le résultat de l'outil.
     */
    public static final class AskUserTool implements AgentTool {
        private final AgentLoop.QuestionGate gate;
        public AskUserTool(AgentLoop.QuestionGate gate) { this.gate = gate; }
        @Override public String name() { return "demander_utilisateur"; }
        @Override public String description() {
            return "Pose 1 à 4 questions à l'utilisateur quand la suite dépend de "
                    + "sa réponse (choix de fichier, préférence, confirmation). "
                    + "Le tour attend sa réponse — ne devine jamais à sa place.";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"questions\":{\"type\":\"array\","
                    + "\"maxItems\":4,\"items\":{\"type\":\"object\",\"properties\":"
                    + "{\"texte\":{\"type\":\"string\",\"description\":\"question claire\"},"
                    + "\"options\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                    + "\"description\":\"options suggérées (facultatif)\"}},"
                    + "\"required\":[\"texte\"]}}},\"required\":[\"questions\"]}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            Object raw = args.raw("questions");
            List<AgentLoop.QuestionGate.Question> questions = new java.util.ArrayList<>();
            if (raw instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof java.util.Map<?, ?> question
                            && question.get("texte") instanceof String text) {
                        List<String> options = new java.util.ArrayList<>();
                        if (question.get("options") instanceof List<?> rawOptions) {
                            for (Object option : rawOptions) {
                                if (option instanceof String s) {
                                    options.add(s);
                                }
                            }
                        }
                        questions.add(new AgentLoop.QuestionGate.Question(text, options));
                    }
                }
            }
            if (questions.isEmpty()) {
                throw new ToolArgs.BadArguments(
                        "aucune question valide : renvoie {\"questions\":[{\"texte\":…}]}");
            }
            if (gate == null) {
                return "Les questions à l'utilisateur ne sont pas disponibles ici : "
                        + "choisis toi-même l'option la plus raisonnable et explique ton choix.";
            }
            String answer = gate.ask(questions);
            return answer == null || answer.isEmpty()
                    ? "L'utilisateur n'a pas répondu. Propose l'option la plus "
                            + "raisonnable en expliquant ton choix."
                    : "Réponse de l'utilisateur : " + answer;
        }
    }

    /** Plan du modèle (Lot 3) : étapes visibles et cochables dans l'UI. */
    public static final class PlanTool implements AgentTool {
        private final java.util.function.Consumer<List<String>> planSink;
        public PlanTool(java.util.function.Consumer<List<String>> planSink) {
            this.planSink = planSink;
        }
        @Override public String name() { return "plan"; }
        @Override public String description() {
            return "Publie ton plan de travail (2 à 8 étapes courtes) : l'utilisateur "
                    + "le voit dans l'atelier. Appelle-le au début d'une tâche "
                    + "multi-étapes, puis à chaque étape terminée.";
        }
        @Override public String schema() {
            return "{\"type\":\"object\",\"properties\":{\"etapes\":{\"type\":\"array\","
                    + "\"minItems\":2,\"maxItems\":8,\"items\":{\"type\":\"string\","
                    + "\"description\":\"une étape courte, à l'infinitif\"}}},"
                    + "\"required\":[\"etapes\"]}";
        }
        @Override public boolean readOnly() { return true; }
        @Override public String run(AgentWorkspace w, ToolArgs args) throws Exception {
            Object raw = args.raw("etapes");
            List<String> steps = new java.util.ArrayList<>();
            if (raw instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof String s && !s.trim().isEmpty()) {
                        steps.add(s.trim());
                    }
                }
            }
            if (steps.size() < 2) {
                throw new ToolArgs.BadArguments(
                        "un plan compte au moins 2 étapes : renvoie {\"etapes\":[…]}");
            }
            planSink.accept(java.util.Collections.unmodifiableList(steps));
            return "Plan publié (" + steps.size() + " étapes) — l'utilisateur le voit. "
                    + "Continue la première étape.";
        }
    }
}
