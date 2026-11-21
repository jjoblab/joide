package jo.joide.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lot 2 du tiroir : classification fine des entrées — racines de sources,
 * packages, XML par parent, secrets, texte, configuration ; affinage Java
 * (classe, abstraite, interface, énumération, annotation) sur un en-tête
 * borné, tolérant aux erreurs de lecture.
 */
class FileKindsTest {

    @TempDir
    Path temp;

    @Test
    void javaRootsAndPackages() {
        assertEquals(FileKind.SRC_ROOT, FileKinds.kindOf("src/main/java", true));
        assertEquals(FileKind.SRC_ROOT, FileKinds.kindOf("app/src/main/java", true));
        assertEquals(FileKind.TEST_ROOT, FileKinds.kindOf("app/src/test/java", true));
        assertEquals(FileKind.TEST_ROOT,
                FileKinds.kindOf("app/src/androidTest/java", true));
        assertEquals(FileKind.PACKAGE, FileKinds.kindOf("src/main/java/com", true));
        assertEquals(FileKind.PACKAGE,
                FileKinds.kindOf("app/src/main/java/com/example/app", true));
        assertEquals(FileKind.PACKAGE,
                FileKinds.kindOf("app/src/test/java/com/example", true));
        // Ressources et autres dossiers : ni racine ni package.
        assertEquals(FileKind.FOLDER, FileKinds.kindOf("src/main/res", true));
        assertEquals(FileKind.FOLDER, FileKinds.kindOf("src/main/resources", true));
        assertEquals(FileKind.FOLDER, FileKinds.kindOf("docs", true));
        // La racine elle-même est un dossier simple.
        assertEquals(FileKind.FOLDER, FileKinds.kindOf(".", true));
    }

    @Test
    void xmlKindsByParentDirectory() {
        assertEquals(FileKind.MANIFEST,
                FileKinds.kindOf("app/src/main/AndroidManifest.xml", false));
        assertEquals(FileKind.RES_LAYOUT,
                FileKinds.kindOf("app/src/main/res/layout/activity_main.xml", false));
        assertEquals(FileKind.RES_LAYOUT,
                FileKinds.kindOf("app/src/main/res/layout-land/main.xml", false));
        assertEquals(FileKind.RES_VALUES,
                FileKinds.kindOf("app/src/main/res/values/strings.xml", false));
        assertEquals(FileKind.RES_DRAWABLE,
                FileKinds.kindOf("app/src/main/res/drawable/ic.xml", false));
        assertEquals(FileKind.XML,
                FileKinds.kindOf("app/src/main/res/menu/main.xml", false));
        assertEquals(FileKind.XML, FileKinds.kindOf("config.xml", false));
    }

    @Test
    void filesByExtensionAndName() {
        assertEquals(FileKind.JAVA_CLASS, FileKinds.kindOf("Main.java", false));
        assertEquals(FileKind.KOTLIN, FileKinds.kindOf("Main.kt", false));
        assertEquals(FileKind.KOTLIN, FileKinds.kindOf("build.kts", false));
        assertEquals(FileKind.GRADLE, FileKinds.kindOf("build.gradle", false));
        assertEquals(FileKind.GRADLE, FileKinds.kindOf("gradle/libs.versions.toml", false));
        assertEquals(FileKind.GRADLE, FileKinds.kindOf("gradlew", false));
        assertEquals(FileKind.GITIGNORE, FileKinds.kindOf(".gitignore", false));
        assertEquals(FileKind.GITIGNORE, FileKinds.kindOf(".gitattributes", false));
        assertEquals(FileKind.TEXT, FileKinds.kindOf("README.md", false));
        assertEquals(FileKind.TEXT, FileKinds.kindOf("LICENSE", false));
        assertEquals(FileKind.TEXT, FileKinds.kindOf("notes.txt", false));
        assertEquals(FileKind.CONFIG, FileKinds.kindOf("forge.json", false));
        assertEquals(FileKind.CONFIG, FileKinds.kindOf("gradle.properties", false));
        assertEquals(FileKind.IMAGE, FileKinds.kindOf("logo.png", false));
        assertEquals(FileKind.ARCHIVE, FileKinds.kindOf("libs.zip", false));
        assertEquals(FileKind.SECRET, FileKinds.kindOf("release.jks", false));
        assertEquals(FileKind.SECRET, FileKinds.kindOf(".env", false));
        assertEquals(FileKind.UNKNOWN, FileKinds.kindOf("binaire.bin", false));
    }

    @Test
    void javaKindByHeadContent() {
        assertEquals(FileKind.JAVA_ANNOTATION,
                FileKinds.javaKindOf("public @interface Doc {}"));
        assertEquals(FileKind.JAVA_INTERFACE,
                FileKinds.javaKindOf("public interface Click {}"));
        assertEquals(FileKind.JAVA_ENUM,
                FileKinds.javaKindOf("public enum Couleur {}"));
        assertEquals(FileKind.JAVA_ABSTRACT,
                FileKinds.javaKindOf("public abstract class Base {}"));
        assertEquals(FileKind.JAVA_CLASS,
                FileKinds.javaKindOf("public class Main {}"));
        // Mots noyés dans un identifiant : pas des déclarations.
        assertEquals(FileKind.JAVA_CLASS,
                FileKinds.javaKindOf("int interfaceCounter = 0;"));
        assertEquals(FileKind.JAVA_CLASS,
                FileKinds.javaKindOf("class Main { enumKind k; }"));
        // Commentaire seul : aucune déclaration trouvée → classe.
        assertEquals(FileKind.JAVA_CLASS,
                FileKinds.javaKindOf("// interface mentionnée en commentaire"));
        assertEquals(FileKind.JAVA_CLASS, FileKinds.javaKindOf(""));
    }

    @Test
    void javaKindRefinedFromBoundedHead() throws IOException {
        Path root = temp.resolve("p");
        Files.createDirectories(root);
        Path iface = root.resolve("Click.java");
        Files.write(iface, "package p;\npublic interface Click {}\n".getBytes());
        assertEquals(FileKind.JAVA_INTERFACE,
                FileKinds.refineJavaKind(FileKind.JAVA_CLASS, iface));
        // Les types non Java ne sont pas affinés (retournés tels quels).
        assertEquals(FileKind.TEXT,
                FileKinds.refineJavaKind(FileKind.TEXT, iface));
        // Fichier illisible : classe simple, jamais de crash.
        Path absent = root.resolve("Absent.java");
        assertEquals(FileKind.JAVA_CLASS,
                FileKinds.refineJavaKind(FileKind.JAVA_CLASS, absent));
    }

    @Test
    void headReadIsBounded() throws IOException {
        Path root = temp.resolve("p");
        Files.createDirectories(root);
        Path big = root.resolve("Big.java");
        byte[] content = new byte[64 * 1024]; // bien plus que la limite de 8 Kio
        java.util.Arrays.fill(content, (byte) 'a');
        content[0] = 'i';
        content[1] = 'n';
        content[2] = 't';
        content[3] = ' ';
        Files.write(big, content);
        String head = FileKinds.readHead(big);
        assertEquals(8 * 1024, head.length(), "l'en-tête est borné à 8 Kio");
    }
}
