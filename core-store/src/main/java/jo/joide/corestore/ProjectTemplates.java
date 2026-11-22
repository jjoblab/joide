package jo.joide.corestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Création de projets depuis modèles (conception §5) : les modèles vivent en
 * code (testables sur JVM) et sont copiés dans le stockage privé à la création.
 * Chaque modèle est un dossier de fichiers relatifs ; le marqueur de mode est le
 * critère d'acceptation L4 : « création depuis un modèle produit un projet
 * buildable ».
 *
 * <p><b>Modèles Forge v0.2.24</b> (format {@code forge.json} réel, calqué sur les
 * exemples vérifiés sur appareil du moteur) : bibliothèque Java, bibliothèque
 * Kotlin, application Android (vues XML, ViewBinding + BuildConfig), application
 * Compose (Kotlin, Material 3) et multi-modules (core + app). Le modèle Gradle
 * reste livré pour le mode à venir (ADR-009 : Forge seul pour le moment).</p>
 *
 * <p><b>Substitution</b> : {@code ${package}} devient le paquet choisi (et
 * {@code a.b.c} → {@code a/b/c} dans les CHEMINS de fichiers), {@code ${name}}
 * le nom du projet (libellés). Un projet créé est immédiatement cohérent :
 * namespace du manifeste, package des sources et arborescence alignés.</p>
 */
public final class ProjectTemplates {

    /** Nature d'un modèle (filtres de la galerie). */
    public enum Kind {

        /** Application complète (APK). */
        APPLICATION,

        /** Bibliothèque (jar). */
        LIBRARY,

        /** Projet Gradle (mode terminal, à venir). */
        GRADLE
    }

    /** Fournisseur de vues d'un modèle Android. */
    public enum Views {

        /** Vues XML + ViewBinding. */
        XML,

        /** Compose (Kotlin). */
        COMPOSE,

        /** Pas de vues (bibliothèque, Gradle). */
        NONE
    }

    /** Un modèle de création : identifiant, nature, fournisseur de vues, fichiers. */
    public record Template(String id, Kind kind, Views views,
                           Map<String, String> files) {

        /** Ce modèle produit-il un projet Forge (forge.json) ? */
        public boolean forge() {
            return kind != Kind.GRADLE;
        }
    }

    private final Map<String, Template> templates;

    /** Modèles standard (testables sur JVM sans assets Android). */
    public static ProjectTemplates standard() {
        List<Template> modèles = List.of(
                forgeJava(), forgeKotlin(), forgeAndroidXml(),
                forgeCompose(), forgeMultimodule(), gradleApp());
        Map<String, Template> parId = new LinkedHashMap<>();
        for (Template modèle : modèles) {
            parId.put(modèle.id(), modèle);
        }
        return new ProjectTemplates(parId);
    }

    // ---- Les modèles ----------------------------------------------------------

    /** Bibliothèque Java pure + main() : compilée et empilée en jar par Forge. */
    private static Template forgeJava() {
        return new Template("forge-java", Kind.LIBRARY, Views.NONE, Map.of(
                "forge.json", """
                        {
                          "modelVersion": 1,
                          "repositories": [
                            "https://repo.maven.apache.org/maven2"
                          ],
                          "modules": [
                            { "name": "app", "type": "java-library" }
                          ]
                        }
                        """,
                "app/src/main/java/${package}/Main.java", """
                        package ${package};

                        /**
                         * Point d'entrée de la bibliothèque : Forge compile ce module
                         * (compileJava) et l'empaquette en jar.
                         */
                        public final class Main {

                            private Main() {
                            }

                            /** Salutation de démonstration.
                             *
                             * @param args arguments de la ligne de commande (ignorés).
                             */
                            public static void main(String[] args) {
                                System.out.println("Bonjour depuis Forge — ${name}");
                            }
                        }
                        """));
    }

    /** Bibliothèque Kotlin pure + main() : compilateur Kotlin embarqué de Forge. */
    private static Template forgeKotlin() {
        return new Template("forge-kotlin", Kind.LIBRARY, Views.NONE, Map.of(
                "forge.json", """
                        {
                          "modelVersion": 1,
                          "repositories": [
                            "https://repo.maven.apache.org/maven2"
                          ],
                          "modules": [
                            {
                              "name": "app",
                              "type": "java-library",
                              "language": "kotlin",
                              "dependencies": [
                                { "maven": "org.jetbrains.kotlin:kotlin-stdlib:2.0.21" }
                              ]
                            }
                          ]
                        }
                        """,
                "app/src/main/kotlin/${package}/Main.kt", """
                        package ${package}

                        /**
                         * Point d'entrée Kotlin : Forge compile Java et Kotlin ensemble
                         * (compilation jointe) et empaquette le module en jar.
                         */
                        fun main() {
                            println("Bonjour depuis Forge (Kotlin) — ${name}")
                        }
                        """));
    }

    /** Application Android, vues XML : ViewBinding + BuildConfig (Forge v0.2.18+). */
    private static Template forgeAndroidXml() {
        return new Template("forge-android-xml", Kind.APPLICATION, Views.XML, Map.of(
                "forge.json", """
                        {
                          "modelVersion": 1,
                          "repositories": [
                            "https://repo.maven.apache.org/maven2",
                            "https://dl.google.com/dl/android/maven2"
                          ],
                          "modules": [
                            {
                              "name": "app",
                              "type": "application",
                              "android": {
                                "manifest": "src/main/AndroidManifest.xml",
                                "res": ["src/main/res"],
                                "minSdk": 28,
                                "targetSdk": 34,
                                "namespace": "${package}",
                                "buildFeatures": {
                                  "buildConfig": true,
                                  "viewBinding": true
                                }
                              }
                            }
                          ]
                        }
                        """,
                "app/src/main/AndroidManifest.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                                package="${package}">
                            <application android:label="@string/app_name">
                                <activity android:name=".MainActivity" android:exported="true">
                                    <intent-filter>
                                        <action android:name="android.intent.action.MAIN"/>
                                        <category android:name="android.intent.category.LAUNCHER"/>
                                    </intent-filter>
                                </activity>
                            </application>
                        </manifest>
                        """,
                "app/src/main/java/${package}/MainActivity.java", """
                        package ${package};

                        import android.app.Activity;
                        import android.os.Bundle;
                        import ${package}.databinding.ActivityMainBinding;

                        /**
                         * Activité principale : la classe de liaison
                         * {@code ActivityMainBinding} (ViewBinding, générée par Forge)
                         * remplace le findViewById.
                         */
                        public final class MainActivity extends Activity {

                            @Override
                            protected void onCreate(Bundle savedInstanceState) {
                                super.onCreate(savedInstanceState);
                                ActivityMainBinding binding =
                                        ActivityMainBinding.inflate(getLayoutInflater());
                                binding.message.setText(
                                        getString(R.string.salutation) + "\\n"
                                                + BuildConfig.VERSION_NAME + " ("
                                                + BuildConfig.VERSION_CODE + ")");
                                setContentView(binding.getRoot());
                            }
                        }
                        """,
                "app/src/main/res/layout/activity_main.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="match_parent"
                                android:gravity="center"
                                android:orientation="vertical">
                            <TextView
                                    android:id="@+id/message"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:textSize="20sp"
                                    android:text="@string/salutation"/>
                        </LinearLayout>
                        """,
                "app/src/main/res/values/strings.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="app_name">${name}</string>
                            <string name="salutation">Bonjour depuis le moteur Forge !</string>
                        </resources>
                        """));
    }

    /** Application Android Compose : Kotlin + Material 3 (plugin compilateur Forge). */
    private static Template forgeCompose() {
        return new Template("forge-compose", Kind.APPLICATION, Views.COMPOSE, Map.of(
                "forge.json", """
                        {
                          "modelVersion": 1,
                          "repositories": [
                            "https://repo.maven.apache.org/maven2",
                            "https://dl.google.com/dl/android/maven2"
                          ],
                          "modules": [
                            {
                              "name": "app",
                              "type": "application",
                              "language": "kotlin",
                              "dependencies": [
                                { "maven": "org.jetbrains.kotlin:kotlin-stdlib:2.0.21" },
                                { "maven": "androidx.activity:activity-compose:1.9.2" },
                                { "maven": "androidx.compose.ui:ui:1.7.1" },
                                { "maven": "androidx.compose.foundation:foundation:1.7.1" },
                                { "maven": "androidx.compose.material3:material3:1.3.0" }
                              ],
                              "android": {
                                "manifest": "src/main/AndroidManifest.xml",
                                "res": ["src/main/res"],
                                "minSdk": 28,
                                "targetSdk": 34,
                                "namespace": "${package}"
                              }
                            }
                          ]
                        }
                        """,
                "app/src/main/AndroidManifest.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                                package="${package}">
                            <application android:label="@string/app_name"
                                    android:theme="@style/Theme.App">
                                <activity android:name=".MainActivity" android:exported="true">
                                    <intent-filter>
                                        <action android:name="android.intent.action.MAIN"/>
                                        <category android:name="android.intent.category.LAUNCHER"/>
                                    </intent-filter>
                                </activity>
                            </application>
                        </manifest>
                        """,
                "app/src/main/kotlin/${package}/MainActivity.kt", """
                        package ${package}

                        import android.os.Bundle
                        import androidx.activity.ComponentActivity
                        import androidx.activity.compose.setContent
                        import androidx.compose.foundation.layout.fillMaxSize
                        import androidx.compose.foundation.layout.padding
                        import androidx.compose.material3.MaterialTheme
                        import androidx.compose.material3.Surface
                        import androidx.compose.material3.Text
                        import androidx.compose.ui.Modifier
                        import androidx.compose.ui.unit.dp

                        /**
                         * Activité Compose : le plugin compilateur Compose de Forge
                         * transforme les fonctions @Composable automatiquement (aucune
                         * configuration — il s'active au classpath Compose).
                         */
                        class MainActivity : ComponentActivity() {

                            override fun onCreate(savedInstanceState: Bundle?) {
                                super.onCreate(savedInstanceState)
                                setContent {
                                    MaterialTheme {
                                        Surface(modifier = Modifier.fillMaxSize()) {
                                            Text(
                                                text = getString(R.string.salutation),
                                                modifier = Modifier.padding(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        """,
                "app/src/main/res/values/strings.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="app_name">${name}</string>
                            <string name="salutation">Salutations de Compose, compilé sur l\'appareil !</string>
                        </resources>
                        """,
                "app/src/main/res/values/styles.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <style name="Theme.App" parent="android:Theme.Material.Light.NoActionBar"/>
                        </resources>
                        """));
    }

    /** Multi-modules : core (bibliothèque Java) + app (Android, ViewBinding). */
    private static Template forgeMultimodule() {
        return new Template("forge-multimodule", Kind.APPLICATION, Views.XML, Map.of(
                "forge.json", """
                        {
                          "modelVersion": 1,
                          "repositories": [
                            "https://repo.maven.apache.org/maven2",
                            "https://dl.google.com/dl/android/maven2"
                          ],
                          "modules": [
                            {
                              "name": "core",
                              "type": "java-library"
                            },
                            {
                              "name": "app",
                              "type": "application",
                              "dependencies": [
                                { "project": "core", "scope": "api" }
                              ],
                              "android": {
                                "manifest": "src/main/AndroidManifest.xml",
                                "res": ["src/main/res"],
                                "minSdk": 28,
                                "targetSdk": 34,
                                "namespace": "${package}",
                                "buildFeatures": {
                                  "buildConfig": true,
                                  "viewBinding": true
                                }
                              }
                            }
                          ]
                        }
                        """,
                "core/src/main/java/${package}/core/Salutations.java", """
                        package ${package}.core;

                        /**
                         * Service du module core : l'application le consomme par la
                         * dépendance de projet ({@code "project": "core"}).
                         */
                        public final class Salutations {

                            private Salutations() {
                            }

                            /** Message d'accueil de l'application.
                             *
                             * @return le message.
                             */
                            public static String app() {
                                return "Salutations de ${name}";
                            }
                        }
                        """,
                "app/src/main/AndroidManifest.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                                package="${package}">
                            <application android:label="@string/app_name">
                                <activity android:name=".MainActivity" android:exported="true">
                                    <intent-filter>
                                        <action android:name="android.intent.action.MAIN"/>
                                        <category android:name="android.intent.category.LAUNCHER"/>
                                    </intent-filter>
                                </activity>
                            </application>
                        </manifest>
                        """,
                "app/src/main/java/${package}/MainActivity.java", """
                        package ${package};

                        import android.app.Activity;
                        import android.os.Bundle;
                        ${package}.core.Salutations;
                        import ${package}.databinding.ActivityMainBinding;

                        /**
                         * Activité principale : consomme le module core (dépendance de
                         * projet) et la classe de liaison générée (ViewBinding).
                         */
                        public final class MainActivity extends Activity {

                            @Override
                            protected void onCreate(Bundle savedInstanceState) {
                                super.onCreate(savedInstanceState);
                                ActivityMainBinding binding =
                                        ActivityMainBinding.inflate(getLayoutInflater());
                                binding.message.setText(Salutations.app() + "\\n"
                                        + BuildConfig.VERSION_NAME + " ("
                                        + BuildConfig.VERSION_CODE + ")");
                                setContentView(binding.getRoot());
                            }
                        }
                        """,
                "app/src/main/res/layout/activity_main.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="match_parent"
                                android:gravity="center"
                                android:orientation="vertical">
                            <TextView
                                    android:id="@+id/message"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:textSize="20sp"
                                    android:text="@string/salutation"/>
                        </LinearLayout>
                        """,
                "app/src/main/res/values/strings.xml", """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="app_name">${name}</string>
                            <string name="salutation">Multi-modules, compilé par Forge</string>
                        </resources>
                        """));
    }

    /** Projet Gradle minimal (mode Gradle + terminal, à venir — conservé). */
    private static Template gradleApp() {
        return new Template("gradle-app", Kind.GRADLE, Views.NONE, Map.of(
                "settings.gradle", """
                        rootProject.name = 'projet'
                        include ':app'
                        """,
                "build.gradle", """
                        plugins { id 'java' }
                        """,
                "app/build.gradle", """
                        plugins { id 'java' }
                        repositories { mavenCentral() }
                        """,
                "app/src/main/java/Main.java", """
                        public class Main {
                            public static void main(String[] args) {
                                System.out.println("Bonjour depuis Gradle");
                            }
                        }
                        """));
    }

    ProjectTemplates(Map<String, Template> templates) {
        this.templates = Map.copyOf(templates);
    }

    // ---- Consultation ---------------------------------------------------------

    /** Les modèles disponibles (l'assistant de création affiche cette liste). */
    public List<Template> all() {
        return new ArrayList<>(templates.values());
    }

    /** Identifiants des modèles (compatibilité : galerie, diagnostics). */
    public List<String> names() {
        return new ArrayList<>(templates.keySet());
    }

    /** Un modèle par identifiant, ou null. */
    public Template find(String id) {
        return templates.get(id);
    }

    // ---- Instanciation --------------------------------------------------------

    /**
     * Instancie un modèle dans le stockage (paquet dérivé du nom de projet :
     * {@code com.example.<nom>}).
     *
     * @param store  stockage des projets.
     * @param templateName identifiant du modèle.
     * @param projectName  nom du projet à créer.
     * @return le dossier du projet créé, buildable (marqueurs présents).
     */
    public Path instantiate(ProjectStore store, String templateName, String projectName)
            throws IOException {
        return instantiate(store, templateName, projectName,
                defaultPackage(projectName));
    }

    /**
     * Instancie un modèle dans le stockage avec le paquet choisi : les espaces de
     * noms (forge.json, manifeste, sources) et l'arborescence des fichiers sont
     * alignés sur ce paquet — le projet créé est cohérent de bout en bout.
     *
     * @param store  stockage des projets.
     * @param templateName identifiant du modèle.
     * @param projectName  nom du projet à créer.
     * @param packageName  paquet Java (ex. {@code com.example.monappli}).
     * @return le dossier du projet créé, buildable (marqueurs présents).
     */
    public Path instantiate(ProjectStore store, String templateName, String projectName,
            String packageName) throws IOException {
        Template template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Modèle inconnu : " + templateName);
        }
        String paquet = packageName == null || packageName.isBlank()
                ? defaultPackage(projectName) : packageName.trim();
        if (!paquet.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")) {
            throw new IllegalArgumentException(
                    "Paquet invalide (attendu a.b.c, minuscules) : " + paquet);
        }
        Path project = store.createProject(projectName);
        for (var entry : template.files().entrySet()) {
            // Chemin : ${package} → arborescence (a.b.c → a/b/c) ; le contenu garde
            // la forme pointée. ${name} → nom du projet (libellés).
            String relativePath = entry.getKey().replace("${package}",
                    paquet.replace('.', '/'));
            String content = entry.getValue()
                    .replace("${package}", paquet)
                    .replace("${name}", projectName);
            Path target = project.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
        return project;
    }

    /** Paquet par défaut dérivé du nom : {@code com.example.monapplication}. */
    public static String defaultPackage(String projectName) {
        String nettoyé = projectName == null ? "" : projectName.trim()
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (nettoyé.isEmpty() || Character.isDigit(nettoyé.charAt(0))) {
            nettoyé = "app" + nettoyé;
        }
        return "com.example." + nettoyé;
    }

    /** Contenu d'un fichier de modèle (diagnostic, tests). */
    public String fileContent(String templateName, String relativePath) {
        Template template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Modèle inconnu : " + templateName);
        }
        String content = template.files().get(relativePath);
        if (content == null) {
            throw new IllegalArgumentException(
                    "Fichier absent du modèle " + templateName + " : " + relativePath);
        }
        return content;
    }
}
