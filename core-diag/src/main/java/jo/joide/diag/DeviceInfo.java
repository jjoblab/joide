package jo.joide.diag;

/**
 * Photographie <b>non identifiante</b> de l'appareil jointe aux rapports de
 * plantage (portage CodeIDE §5.8, ADR-021) — pas de numéro de série, pas
 * d'identifiant publicitaire, pas d'adresse réseau.
 *
 * @param manufacturer      fabricant (Build.MANUFACTURER côté coquille)
 * @param model             modèle (Build.MODEL)
 * @param androidVersion    version d'Android affichée (Build.VERSION.RELEASE)
 * @param apiLevel          niveau d'API (Build.VERSION.SDK_INT)
 * @param abi               première ABI supportée
 * @param locale            étiquette de langue de l'appareil
 * @param maxMemoryBytes    tas maximum du processus (Runtime.maxMemory)
 * @param freeMemoryBytes   tas libre au moment de la capture
 * @param storageFreeBytes  stockage disponible sous filesDir (StatFs)
 */
public record DeviceInfo(String manufacturer, String model, String androidVersion,
        int apiLevel, String abi, String locale, long maxMemoryBytes, long freeMemoryBytes,
        long storageFreeBytes) {

    /** Valeur de repli quand la photographie échoue — jamais un échec du rapport. */
    public static DeviceInfo unknown() {
        return new DeviceInfo("unknown", "unknown", "unknown", 0, "unknown",
                "unknown", 0L, 0L, 0L);
    }
}
