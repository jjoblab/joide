package jo.joide.files;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lot 3 du tiroir : contenus de départ des nouveaux fichiers — squelette
 * Java (package déduit du dossier), Kotlin, XML, Markdown ; vide ailleurs.
 */
class FileTemplatesTest {

    @Test
    void javaClassWithPackage() {
        assertEquals("package com.example.app;\n\npublic class Main {\n}\n",
                FileTemplates.contentFor("Main.java", "com/example/app"));
    }

    @Test
    void javaClassAtRoot() {
        assertEquals("public class Main {\n}\n",
                FileTemplates.contentFor("Main.java", "."));
    }

    @Test
    void kotlinFile() {
        assertEquals("package com.example.app\n\nclass Util\n",
                FileTemplates.contentFor("Util.kt", "com/example/app"));
    }

    @Test
    void xmlAndMarkdown() {
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n",
                FileTemplates.contentFor("activity.xml", "app/src/main/res/layout"));
        assertEquals("# Notes\n",
                FileTemplates.contentFor("Notes.md", "."));
    }

    @Test
    void otherFilesAreEmpty() {
        assertEquals("", FileTemplates.contentFor("data.json", "."));
        assertEquals("", FileTemplates.contentFor("script", "."));
    }
}
