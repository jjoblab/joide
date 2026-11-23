package jo.joide.buildforge;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jo.forge.json.Json;
import jo.forge.json.JsonException;

/**
 * Recherche de dépendances en ligne (lot L10, d'après le parcours d'ajout de la
 * référence — REF-129) : interrogation du **solr de Maven Central**
 * ({@code search.maven.org}), la même source publique que la référence —
 * Google Maven n'est PAS interrogé (l'écran le dit honnêtement).
 *
 * <p>Pur JVM, dépendance réseau explicite : chaque appel a un timeout (8 s comme
 * la référence) ; un échec réseau porte un diagnostic (« index injoignable »,
 * distinct de « aucun résultat » — la référence distingue les deux et nous
 * aussi). L'analyseur JSON est celui de la bibliothèque Forge
 * ({@code jo.forge:json}, ADR-001 : Forge consommé comme bibliothèque
 * versionnée).</p>
 *
 * <p>Deux requêtes : {@link #search(String, int)} (artefacts par terme —
 * {@code q=<terme>}) et {@link #versions(String, String, int)} (versions
 * publiées d'une coordonnée — {@code q=g:"g" AND a:"a"} sur le cœur
 * {@code gav}, plus récentes en tête).</p>
 */
public final class MavenCentralSearch {

    /** Un artefact trouvé (groupe, nom, dernière version, packaging). */
    public record Artifact(String group, String artifact, String version,
            String packaging) {

        /** Coordonnée affichée {@code g:a:dernièreVersion}. */
        public String coordinate() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /** Une version publiée d'une coordonnée. */
    public record Version(String version, long timestampMs) {}

    /** Échec d'accès à l'index (réseau) — distinct de « aucun résultat ». */
    public static final class IndexInjoignable extends IOException {
        IndexInjoignable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String baseSolr;
    private final Duration timeout;

    /** Recherche sur le solr public de Maven Central (timeout 8 s). */
    public MavenCentralSearch() {
        this("https://search.maven.org/solrsearch/select");
    }

    /**
     * Recherche sur un solr donné (tests : serveur local).
     *
     * @param baseSolr URL du point {@code /solrsearch/select}.
     */
    public MavenCentralSearch(String baseSolr) {
        this(baseSolr, Duration.ofSeconds(8));
    }

    private MavenCentralSearch(String baseSolr, Duration timeout) {
        this.baseSolr = baseSolr;
        this.timeout = timeout;
    }

    /**
     * Cherche des artefacts par terme libre (nom, groupe — comme la saisie de
     * la référence).
     *
     * @param term  terme cherché (vide : liste vide, aucune requête).
     * @param limit nombre maximum de résultats demandé.
     * @return les artefacts trouvés (peut être vide : « aucun résultat »).
     * @throws IndexInjoignable si l'index n'a pas répondu (réseau/HTTP/JSON).
     */
    public List<Artifact> search(String term, int limit) throws IOException {
        if (term == null || term.isBlank() || limit <= 0) {
            return List.of();
        }
        String query = URLEncoder.encode(term.trim(), StandardCharsets.UTF_8);
        Map<String, Object> racine = interroger(
                "?q=" + query + "&rows=" + limit + "&wt=json");
        return lireArtefacts(racine);
    }

    /**
     * Versions publiées d'une coordonnée, plus récentes en tête.
     *
     * @throws IndexInjoignable si l'index n'a pas répondu.
     */
    public List<Version> versions(String group, String artifact, int limit)
            throws IOException {
        if (group == null || artifact == null || group.isBlank() || artifact.isBlank()
                || limit <= 0) {
            return List.of();
        }
        String requête = "g:\"" + group + "\" AND a:\"" + artifact + "\"";
        String query = URLEncoder.encode(requête, StandardCharsets.UTF_8);
        Map<String, Object> racine = interroger(
                "?q=" + query + "&core=gav&rows=" + limit + "&wt=json");
        return lireVersions(racine);
    }

    // ---- Interrogation ------------------------------------------------------

    private Map<String, Object> interroger(String paramètres) throws IOException {
        HttpURLConnection connexion = null;
        try {
            connexion = (HttpURLConnection) new URL(URI.create(
                    baseSolr + paramètres).toASCIIString()).openConnection();
            connexion.setConnectTimeout((int) timeout.toMillis());
            connexion.setReadTimeout((int) timeout.toMillis());
            connexion.setRequestProperty("Accept", "application/json");
            int code = connexion.getResponseCode();
            if (code != 200) {
                throw new IndexInjoignable("Maven Central a répondu " + code
                        + " (index de dépendances injoignable)", null);
            }
            String corps = new String(connexion.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            Object racine;
            try {
                racine = Json.parse(corps);
            } catch (JsonException e) {
                throw new IndexInjoignable("Réponse illisible de Maven Central", e);
            }
            if (!(racine instanceof Map)) {
                throw new IndexInjoignable("Réponse inattendue de Maven Central", null);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) racine;
            return map;
        } catch (IOException réseau) {
            if (réseau instanceof IndexInjoignable) {
                throw réseau;
            }
            throw new IndexInjoignable("Maven Central injoignable (réseau)",
                    réseau);
        } finally {
            if (connexion != null) {
                connexion.disconnect();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Artifact> lireArtefacts(Map<String, Object> racine) {
        List<Artifact> résultats = new ArrayList<>();
        for (Object doc : docsDe(racine)) {
            if (doc instanceof Map<?, ?> entrée) {
                String group = texte(entrée.get("g"));
                String artifact = texte(entrée.get("a"));
                String version = texte(entrée.get("latestVersion"));
                if (group != null && artifact != null && version != null) {
                    résultats.add(new Artifact(group, artifact, version,
                            texte(entrée.get("p"))));
                }
            }
        }
        return List.copyOf(résultats);
    }

    @SuppressWarnings("unchecked")
    private static List<Version> lireVersions(Map<String, Object> racine) {
        List<Version> résultats = new ArrayList<>();
        for (Object doc : docsDe(racine)) {
            if (doc instanceof Map<?, ?> entrée) {
                String version = texte(entrée.get("v"));
                if (version != null) {
                    long horodatage = entrée.get("timestamp") instanceof Number n
                            ? n.longValue() : 0L;
                    résultats.add(new Version(version, horodatage));
                }
            }
        }
        return List.copyOf(résultats);
    }

    private static List<?> docsDe(Map<String, Object> racine) {
        if (racine.get("response") instanceof Map<?, ?> réponse
                && réponse.get("docs") instanceof List<?> docs) {
            return docs;
        }
        return List.of();
    }

    private static String texte(Object valeur) {
        return valeur instanceof String texte ? texte : null;
    }
}
