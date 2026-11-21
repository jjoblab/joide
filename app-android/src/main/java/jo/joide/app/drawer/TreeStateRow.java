package jo.joide.app.drawer;

/**
 * Une rangée d'état de l'arborescence (Lot 3) : les états se disent
 * toujours — chargement (spinner par dossier), erreur (avec Réessayer),
 * dossier vide, projet vide, listing tronqué (plafond), aucun résultat de
 * filtre. Jamais d'état muet.
 */
enum TreeState { LOADING, ERROR, EMPTY_DIR, EMPTY_PROJECT, TRUNCATED, FILTER_EMPTY, ANDROID_EMPTY }

record TreeStateRow(TreeState state, String relativePath, String message, int depth) {
}
