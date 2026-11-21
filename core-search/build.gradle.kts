// core-search — JVM pur : recherche dans le projet (Lot 4 du tiroir).
// Texte littéral ou expression régulière, casse, mot entier, portée, globs
// d'inclusion/exclusion, flux poussé au fur et à mesure, borné et annulable.
// Aucune dépendance runtime hors core-files (politique de licences) ;
// Java 17 verrouillé comme les autres modules (D8/AGP).
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
    api(project(":core-files"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
