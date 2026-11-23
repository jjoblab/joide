package jo.joide.workbench;

import java.util.List;

/**
 * Le descripteur d'une tâche telle que le MOTEUR la présente (Forge v0.2.27,
 * protocole 5, décision D-22 de Forge) : le répertoire des tâches appartient
 * au moteur — l'atelier affiche, il ne devine plus.
 *
 * <p>Avant un build, l'atelier demande CE QUI PEUT TOURNER (noms, groupes,
 * descriptions, dépendances) et peut laisser l'utilisateur choisir : la
 * sélection repart vers le moteur dans {@code BuildRequest.tasks()} — la
 * clôture des dépendances se calcule côté moteur, JAMAIS côté atelier.</p>
 *
 * <p>Les tâches de cycle de vie ({@code assemble}, {@code clean}) sont des
 * descripteurs comme les autres, marqués {@code lifecycle} : l'affichage
 * honnête distingue une tâche d'agrégation d'une étape de compilation.</p>
 *
 * @param name         nom complet et unique (ex. {@code app:compileJava},
 *                     {@code assemble}) — jamais nul ni vide.
 * @param group        groupe d'affichage (ex. {@code compilation}) ; jamais
 *                     nul (vide = sans groupe).
 * @param description  une ligne humaine qui dit CE QUE FAIT la tâche ; jamais nulle.
 * @param dependencies noms des tâches dont celle-ci dépend (directement) ; jamais nulle.
 * @param lifecycle    vrai pour une tâche de cycle de vie (agrégat ou nettoyage).
 */
public record TaskInfo(String name, String group, String description,
                       List<String> dependencies, boolean lifecycle) {

    public TaskInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Nom de tâche absent");
        }
        group = group == null ? "" : group;
        description = description == null ? "" : description;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
