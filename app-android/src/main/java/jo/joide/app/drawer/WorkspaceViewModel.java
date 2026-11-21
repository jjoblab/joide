package jo.joide.app.drawer;

import androidx.lifecycle.ViewModel;
import jo.joide.workbench.WorkspaceEvents;

/**
 * État partagé du tiroir gauche (Lot 0) : chemin du projet — source unique
 * pour les onglets — et bus d'événements de l'espace de travail. Portée :
 * tiroir (survit aux changements d'onglet et à la rotation ; le fragment
 * tiroir vit aussi longtemps que l'atelier qui l'héberge).
 */
public class WorkspaceViewModel extends ViewModel {

    private String projectPath;
    private final WorkspaceEvents events = new WorkspaceEvents();

    /** Pose le chemin du projet (une seule fois : l'atelier ne change pas). */
    public void init(String path) {
        if (projectPath == null) {
            projectPath = path;
        }
    }

    /** Chemin du projet ouvert ; null hors atelier. */
    public String projectPath() {
        return projectPath;
    }

    /** Bus d'événements de l'espace de travail (un seul chemin de mise à jour). */
    public WorkspaceEvents events() {
        return events;
    }
}
