// core-files — JVM pur : arborescence de fichiers du projet (Lot 1 du tiroir).
// Aucune dépendance runtime (politique de licences) ; Java 8 strict (API 28,
// le module tourne sur l'appareil sans désucrage — ADR-009).
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile> {
    // Comme le module ai : records du language, bytecode verrouillé pour D8/AGP.
options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
