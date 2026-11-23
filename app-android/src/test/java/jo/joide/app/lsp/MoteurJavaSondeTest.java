package jo.joide.app.lsp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jo.codeide.lsp.java.EnvironnementCompilation;
import jo.codeide.lsp.java.MoteurJava;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * La sonde du moteur Java nu (LOT-11) : le diagnose DIRECT, sans protocole —
 * l'exception éventuelle du ecj patché s'y lit en clair (le publicateur du
 * protocole l'avalerait en silence dans le fil de l'ordonnanceur).
 */
class MoteurJavaSondeTest {

    @TempDir
    Path dossier;

    @Test
    void leDiagnoseNuRendLErreurDeMéthodeInconnue() throws Exception {
        Path androidJar = dossier.resolve("android.jar");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(androidJar))) {
            for (int i = 0; i < 50; i++) {
                zip.putNextEntry(new java.util.zip.ZipEntry(
                        "android/factice/P" + i + ".class"));
                zip.closeEntry();
            }
        }
        EnvironnementCompilation environnement =
                new EnvironnementCompilation(null, "17");
        environnement.remplacerContexteModule(null, List.of(),
                List.of(androidJar));
        MoteurJava moteur = environnement.moteur(null);
        String texte = """
                package demo;

                public class A {
                    int appelInconnu() {
                        return methodeInexistante();
                    }
                }
                """;
        List<jo.codeide.lsp.java.diagnostics.Diagnostic> diagnostics =
                moteur.diagnose("A.java", texte);
        assertTrue(diagnostics.stream().anyMatch(d ->
                        d.getMessage().toLowerCase().contains("inexistante")),
                "l'erreur attendue se dit : " + diagnostics);
    }
}
