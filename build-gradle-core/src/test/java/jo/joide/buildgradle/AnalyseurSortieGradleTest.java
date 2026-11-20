package jo.joide.buildgradle;

import java.util.List;
import jo.joide.workbench.Diagnostic;
import jo.joide.workbench.EvenementBuild;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalyseurSortieGradleTest {

    @Test
    void traduitUnDiagnosticJavacAvecPosition() {
        EvenementBuild e = AnalyseurSortieGradle.analyser(
                "app/src/main/java/demo/Main.java:12: error: cannot find symbol");
        assertInstanceOf(EvenementBuild.DiagnosticRecu.class, e);
        Diagnostic d = ((EvenementBuild.DiagnosticRecu) e).diagnostic();
        assertEquals(Diagnostic.Severite.ERREUR, d.severite());
        assertEquals("app/src/main/java/demo/Main.java", d.fichier());
        assertEquals(12, d.ligne());
        assertEquals("cannot find symbol", d.message());
    }

    @Test
    void traduitUnDiagnosticDuPluginAndroid() {
        EvenementBuild e = AnalyseurSortieGradle.analyser(
                "/projet/app/src/main/res/layout/main.xml: (3, 12): error: malformed XML");
        Diagnostic d = ((EvenementBuild.DiagnosticRecu) e).diagnostic();
        assertEquals(Diagnostic.Severite.ERREUR, d.severite());
        assertEquals(3, d.ligne());
        assertEquals(12, d.colonne());
        assertEquals("malformed XML", d.message());
    }

    @Test
    void laLigneOrdinaireResteDuJournal() {
        assertInstanceOf(EvenementBuild.Journal.class,
                AnalyseurSortieGradle.analyser("> Task :app:compileJava"));
        assertInstanceOf(EvenementBuild.Journal.class,
                AnalyseurSortieGradle.analyser("BUILD SUCCESSFUL in 12s"));
    }

    @Test
    void detecteLEchecDeBuild() {
        assertTrue(AnalyseurSortieGradle.echecConstater(
                List.of("> Task :app:compileJava", "FAILURE: Build failed with an error.")));
        assertFalse(AnalyseurSortieGradle.echecConstater(List.of("BUILD SUCCESSFUL")));
    }
}
