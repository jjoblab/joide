package jo.joide.app.config;

import jo.joide.coremodel.ForgeJson;

/**
 * Contrat des feuilles de configuration ↔ écran Modules (lot L10) : les
 * feuilles mutent le **modèle réel** ({@link ForgeJson.Model} — champs
 * inconnus préservés, fidélité prouvée par le banc) et préviennent l'hôte,
 * qui marque l'état non enregistré et re-rend. L'écriture disque n'a lieu
 * QUE sur « Enregistrer » (barre épinglée).
 */
public interface ModelHost {

    /** Le modèle courant (mutations en mémoire). */
    ForgeJson.Model model();

    /** Une mutation a eu lieu : état non enregistré + re-rendu. */
    void mutate(String description);
}
