package jo.joide.ai.provider;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Migration des identifiants historiques (Lot 2) : l'ancien fournisseur
 * « passerelle » devient « custom » (URL, modèle et clé conservés) ;
 * « laboratoire » était un identifiant de maquette — il disparaît, et une clé
 * qui y dormait est signalée une fois à l'utilisateur. Pur : les accès aux
 * préférences et au coffre passent par des fonctions injectées (testables).
 */
public final class ProviderMigration {

    private ProviderMigration() {}

    /**
     * Migre les préférences vers les identifiants du registre.
     *
     * @param read  lecture d'une préférence (null si absente).
     * @param write écriture d'une préférence.
     * @return vrai si quelque chose a changé (à persister).
     */
    public static boolean migrateIds(Function<String, String> read,
            BiConsumer<String, String> write) {
        boolean changed = false;
        if ("passerelle".equals(read.apply("fournisseur"))) {
            write.accept("fournisseur", "custom");
            changed = true;
        }
        changed |= carryOver(read, write, "modele_passerelle", "modele_custom");
        changed |= carryOver(read, write, "base_url_passerelle", "base_url_custom");
        return changed;
    }

    /** Reporte une ancienne valeur si la nouvelle n'existe pas. */
    private static boolean carryOver(Function<String, String> read,
            BiConsumer<String, String> write, String oldKey, String newKey) {
        String old = read.apply(oldKey);
        if (old != null && !old.isEmpty() && read.apply(newKey) == null) {
            write.accept(newKey, old);
            return true;
        }
        return false;
    }

    /**
     * Vrai si une clé dort sous l'ancien identifiant « laboratoire » : la
     * coquille l'affiche une fois puis propose de la supprimer (l'identifiant
     * n'existe plus dans le registre).
     */
    public static boolean hasLegacyLaboratoireKey(Function<String, String> keyReader) {
        return keyReader.apply("laboratoire") != null;
    }
}
