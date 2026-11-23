package jo.joide.buildforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Banc de la recherche Maven Central (L10) : serveur HTTP local qui joue le solr —
 * lecture des artefacts, des versions (cœur gav), requête vide = aucune requête
 * réseau, échec réseau = {@link MavenCentralSearch.IndexInjoignable} (distinct
 * de « aucun résultat »).
 */
class MavenCentralSearchTest {

    private HttpServer serveur;
    private final AtomicInteger requêtes = new AtomicInteger();

    @AfterEach
    void arrêter() {
        if (serveur != null) {
            serveur.stop(0);
        }
    }

    @Test
    void litArtefactsEtVersions() throws Exception {
        // Deux cœurs du solr, comme le vrai : sans core=gav les docs portent
        // latestVersion ; avec core=gav ils portent v (+ timestamp).
        String réponseArtefacts = """
                {"response":{"numFound":2,"docs":[
                  {"g":"com.exemple","a":"outils","latestVersion":"2.1.0","p":"jar"},
                  {"g":"org.autre","a":"biblio","latestVersion":"5.0","p":"aar"}]}}
                """;
        String réponseVersions = """
                {"response":{"numFound":2,"docs":[
                  {"v":"2.1.0","timestamp":1735000000},
                  {"v":"1.8.2","timestamp":1700000000}]}}
                """;
        démarreServeur(réponseArtefacts, réponseVersions);
        MavenCentralSearch recherche = new MavenCentralSearch("http://127.0.0.1:"
                + serveur.getAddress().getPort() + "/solrsearch/select");
        List<MavenCentralSearch.Artifact> artefacts = recherche.search("outils", 20);
        assertEquals(2, artefacts.size());
        assertEquals("com.exemple", artefacts.get(0).group());
        assertEquals("outils", artefacts.get(0).artifact());
        assertEquals("2.1.0", artefacts.get(0).version());
        assertEquals("jar", artefacts.get(0).packaging());
        assertEquals("com.exemple:outils:2.1.0", artefacts.get(0).coordinate());

        // Versions (cœur gav) : plus récentes en tête.
        List<MavenCentralSearch.Version> versions = recherche.versions(
                "com.exemple", "outils", 10);
        assertEquals(2, versions.size());
        assertEquals("2.1.0", versions.get(0).version());
        assertEquals(1735000000L, versions.get(0).timestampMs());
        assertEquals(2, requêtes.get()); // une requête par appel, pas de rafale
    }

    @Test
    void requêteVideNePartJamais() throws Exception {
        démarreServeur("{\"response\":{\"docs\":[]}}",
                "{\"response\":{\"docs\":[]}}");
        MavenCentralSearch recherche = new MavenCentralSearch("http://127.0.0.1:"
                + serveur.getAddress().getPort() + "/solrsearch/select");
        assertTrue(recherche.search("  ", 10).isEmpty());
        assertTrue(recherche.versions(null, "a", 10).isEmpty());
        assertEquals(0, requêtes.get());
    }

    @Test
    void échecRéseauDiagnosticIndexInjoignable() throws Exception {
        // Port fermé : personne n'écoute — l'échec porte le diagnostic réseau.
        MavenCentralSearch recherche = new MavenCentralSearch(
                "http://127.0.0.1:9/solrsearch/select");
        IOException erreur = assertInstanceOf(IOException.class,
                assertThrows(() -> recherche.search("outils", 10)));
        assertInstanceOf(MavenCentralSearch.IndexInjoignable.class, erreur);
        assertTrue(erreur.getMessage().contains("injoignable"));
    }

    @Test
    void aucunRésultatEstUneListeVidePasUneErreur() throws Exception {
        démarreServeur("{\"response\":{\"numFound\":0,\"docs\":[]}}",
                "{\"response\":{\"numFound\":0,\"docs\":[]}}");
        MavenCentralSearch recherche = new MavenCentralSearch("http://127.0.0.1:"
                + serveur.getAddress().getPort() + "/solrsearch/select");
        assertTrue(recherche.search("introuvable", 10).isEmpty());
    }

    // ---- Montage -------------------------------------------------------------

    private void démarreServeur(String corpsArtefacts, String corpsVersions)
            throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", échange -> {
            requêtes.incrementAndGet();
            // Le vrai solr route par cœur : core=gav = versions d'une coordonnée.
            boolean cœurGav = échange.getRequestURI().toString().contains("core=gav");
            byte[] charge = (cœurGav ? corpsVersions : corpsArtefacts)
                    .getBytes(StandardCharsets.UTF_8);
            échange.getResponseHeaders().set("Content-Type", "application/json");
            échange.sendResponseHeaders(200, charge.length);
            échange.getResponseBody().write(charge);
            échange.close();
        });
        serveur.start();
    }

    /** AssertThrows sans import statique supplémentaire (lisible). */
    private static Exception assertThrows(
            org.junit.jupiter.api.function.Executable exécutable) {
        return org.junit.jupiter.api.Assertions.assertThrows(Exception.class, exécutable);
    }
}
