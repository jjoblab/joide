package jo.joide.buildgradle;

import java.util.List;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.Diagnostic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleOutputParserTest {

    @Test
    void translatesJavacDiagnosticWithPosition() {
        BuildEvent e = GradleOutputParser.parse(
                "app/src/main/java/demo/Main.java:12: error: cannot find symbol");
        assertInstanceOf(BuildEvent.DiagnosticEvent.class, e);
        Diagnostic d = ((BuildEvent.DiagnosticEvent) e).diagnostic();
        assertEquals(Diagnostic.Severity.ERROR, d.severity());
        assertEquals("app/src/main/java/demo/Main.java", d.file());
        assertEquals(12, d.line());
        assertEquals("cannot find symbol", d.message());
    }

    @Test
    void translatesAndroidPluginDiagnostic() {
        BuildEvent e = GradleOutputParser.parse(
                "/projet/app/src/main/res/layout/main.xml: (3, 12): error: malformed XML");
        Diagnostic d = ((BuildEvent.DiagnosticEvent) e).diagnostic();
        assertEquals(Diagnostic.Severity.ERROR, d.severity());
        assertEquals(3, d.line());
        assertEquals(12, d.column());
        assertEquals("malformed XML", d.message());
    }

    @Test
    void ordinaryLineRemainsLog() {
        assertInstanceOf(BuildEvent.Log.class,
                GradleOutputParser.parse("> Task :app:compileJava"));
        assertInstanceOf(BuildEvent.Log.class,
                GradleOutputParser.parse("BUILD SUCCESSFUL in 12s"));
    }

    @Test
    void detectsBuildFailure() {
        assertTrue(GradleOutputParser.isBuildFailure(
                List.of("> Task :app:compileJava", "FAILURE: Build failed with an error.")));
        assertFalse(GradleOutputParser.isBuildFailure(List.of("BUILD SUCCESSFUL")));
    }
}
