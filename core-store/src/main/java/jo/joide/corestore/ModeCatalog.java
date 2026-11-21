package jo.joide.corestore;

import java.util.List;
import java.util.Locale;

/**
 * Catalogue des composants d'un mode (écran « Installation du mode » du studio
 * de previews, d'après l'assistant d'installation du moteur de Forge et
 * l'installateur Gradle : phases vérifiées « installé = vérifié »). JVM pur :
 * tailles et descriptions par composant, phases de vérification du mode
 * Gradle, totaux — l'écran n'invente jamais une taille (elle vient d'ici,
 * remplaçable par le manifeste signé du canal à l'implémentation complète).
 */
public final class ModeCatalog {

    /** Un composant téléchargé (identifiant, description, taille en octets). */
    public record Component(String id, String description, long bytes) {

        /** Taille lisible (« 38,1 Mo »). */
        public String humanSize() {
            return String.format(Locale.FRANCE, "%.1f Mo", bytes / 1024.0 / 1024.0);
        }
    }

    /** Une phase de vérification Gradle (installé = vérifié, ADR-0085). */
    public record Phase(String name, String verificationCommand) { }

    private final List<Component> components;
    private final List<Phase> phases;
    private final long freeBytesRequired;

    private ModeCatalog(List<Component> components, List<Phase> phases,
            long freeBytesRequired) {
        this.components = components;
        this.phases = phases;
        this.freeBytesRequired = freeBytesRequired;
    }

    /** Catalogue du mode Forge (moteur + runtime + dexeur + signature). */
    public static ModeCatalog forge() {
        return new ModeCatalog(
                List.of(
                        new Component("moteur-0.2.13",
                                "Cœur du moteur + journal", 39_953_000L),
                        new Component("jre-21",
                                "Runtime Java (temurin aarch64)", 43_716_000L),
                        new Component("java-compiler",
                                "API java.compiler corrigée ART", 2_411_000L),
                        new Component("ecj-3.36",
                                "Compilateur Java (EPL-2.0)", 10_278_000L),
                        new Component("d8-r8",
                                "Dexeur (Apache-2.0)", 6_710_000L),
                        new Component("apksig",
                                "Signature APK (Apache-2.0)", 4_300_000L)),
                List.of(),
                220_000_000L);
    }

    /** Catalogue du mode Gradle (préfixe bootstrap, outils, JDK, distribution). */
    public static ModeCatalog gradle() {
        return new ModeCatalog(
                List.of(
                        new Component("bootstrap",
                                "Archive Linux de base (préfixe usr/)", 19_082_000L),
                        new Component("outils",
                                "tar, xz, curl (paquets du préfixe)", 9_857_000L),
                        new Component("jdk",
                                "JDK 21 (distribution complète)", 81_684_000L),
                        new Component("gradle",
                                "Gradle 8.11.1 + AGP 8.7.3", 44_541_000L)),
                List.of(
                        new Phase("BOOTSTRAP", "bin/sh -c 'echo ok'"),
                        new Phase("OUTILS", "bin/tar --version"),
                        new Phase("JAVA", "bin/java -version"),
                        new Phase("GRADLE", "bin/gradle --version")),
                430_000_000L);
    }

    /** Composants (l'écran les liste avec leurs tailles). */
    public List<Component> components() {
        return components;
    }

    /** Phases de vérification (mode Gradle : installé = vérifié). */
    public List<Phase> phases() {
        return phases;
    }

    /** Total téléchargé. */
    public long totalBytes() {
        return components.stream().mapToLong(Component::bytes).sum();
    }

    /** Total lisible (« 102,4 Mo »). */
    public String humanTotal() {
        return String.format(Locale.FRANCE, "%.1f Mo",
                totalBytes() / 1024.0 / 1024.0);
    }

    /** Espace libre disque requis après installation. */
    public long freeBytesRequired() {
        return freeBytesRequired;
    }
}
