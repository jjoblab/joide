// core-outline — JVM pur : plan du fichier actif (Lot 5 du tiroir).
// Java (classes, membres, visibilité), XML (éléments et android:id),
// Markdown (titres), JSON (clés) — tolérant au code incomplet : un
// analyseur en lignes, jamais d'échec. Aucune dépendance runtime.
plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
