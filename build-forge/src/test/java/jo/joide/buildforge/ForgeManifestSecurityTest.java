package jo.joide.buildforge;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import jo.forge.setup.manifest.CanalMoteur;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.ManifestCodec;
import jo.forge.setup.manifest.ManifestVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrôle d'intégrité du canal manifeste (L3) : vraie paire de clés ECDSA P-256,
 * vrai serveur HTTP local — exactement le protocole de Forge (CanalMoteur +
 * ManifestVerifier). Un manifeste falsifié doit être REFUSÉ, jamais accepté en
 * silence : la clé épinglée côté JoIDE ne correspond plus.
 */
class ForgeManifestSecurityTest {

    private HttpServer server;
    private KeyPair pair;
    private String publicKeyBase64;
    private final AtomicInteger signatureRequests = new AtomicInteger();

    @TempDir
    Path folder;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        pair = generator.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ---- Fabriques ---------------------------------------------------------

    /** Manifeste minimal valide (codec officiel de Forge). */
    private String manifestJson(String engineVersion) {
        ComponentManifest component = new ComponentManifest("engine-core",
                engineVersion, 42, "a".repeat(64),
                List.of("https://exemple.fr/engine-core.zip"), List.of("all"), 1,
                List.of());
        return ManifestCodec.write(new EngineManifest(1, engineVersion, 1,
                "2026-11-20", List.of(component)));
    }

    /** Signe des octets avec la clé privée de test (SHA-256 avec ECDSA, DER, base64). */
    private String sign(byte[] octets) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(octets);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    /** Sert un manifeste + signature sur le serveur local ; retourne l'URL du manifeste. */
    private String serve(String manifestBody, String signature) throws Exception {
        byte[] octets = manifestBody.getBytes(StandardCharsets.UTF_8);
        server.createContext("/manifest.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, octets);
        });
        server.createContext("/manifest.sig", exchange -> {
            signatureRequests.incrementAndGet();
            respond(exchange, signature.getBytes(StandardCharsets.UTF_8));
        });
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/manifest.json";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
            byte[] body) throws java.io.IOException {
        try (OutputStream out = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(200, body.length);
            out.write(body);
        }
    }

    /** Canal de test avec le schéma HTTP autorisé (serveur local) et la clé de test. */
    private CanalMoteur testChannel() {
        return new CanalMoteur(publicKeyBase64, Set.of("http"));
    }

    // ---- Scénarios ---------------------------------------------------------

    @Test
    void signedManifestIsAcceptedAndVerified() throws Exception {
        String manifest = manifestJson("0.2.6-test");
        String url = serve(manifest, sign(manifest.getBytes(StandardCharsets.UTF_8)));
        CanalMoteur.ManifesteSigne signed = testChannel().telecharger(url);
        assertNotNull(signed);
        assertEquals("0.2.6-test", signed.manifeste().engineVersion());
        // Vérification explicite par la clé épinglée : vraie signature, acceptée.
        assertTrue(new ManifestVerifier(publicKeyBase64)
                .verify(signed.octets(), signed.signatureBase64()));
        assertEquals(1, signatureRequests.get(), "la signature a été téléchargée une fois");
    }

    @Test
    void tamperedManifestIsRejected() throws Exception {
        // Manifeste signé par la MAUVAISE clé (attaquant) : la vérification épinglée
        // doit refuser. JoIDE ne doit jamais installer un moteur décrit par un
        // manifeste non signé par la clé attendue (conception §4.2).
        String manifest = manifestJson("0.2.6-evil");
        KeyPairGenerator attackerGenerator = KeyPairGenerator.getInstance("EC");
        attackerGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair attackerPair = attackerGenerator.generateKeyPair();
        Signature attackerSigner = Signature.getInstance("SHA256withECDSA");
        attackerSigner.initSign(attackerPair.getPrivate());
        attackerSigner.update(manifest.getBytes(StandardCharsets.UTF_8));
        String attackerSignature = Base64.getEncoder()
                .encodeToString(attackerSigner.sign());

        String url = serve(manifest, attackerSignature);
        // Le canal (clé épinglée = la nôtre) rejette la signature de l'attaquant.
        assertThrows(Exception.class, () -> testChannel().telecharger(url));
        // La vérification directe dit la même chose : faux, jamais muet.
        assertTrue(!new ManifestVerifier(publicKeyBase64)
                .verify(manifest.getBytes(StandardCharsets.UTF_8), attackerSignature));
    }

    @Test
    void manifestModifiedAfterSigningIsRejected() throws Exception {
        // Attaque différente : manifeste légitimement signé, PUIS modifié (octets
        // changés après signature). La signature ne correspond plus aux octets.
        String signedBody = manifestJson("0.2.6-test");
        String signature = sign(signedBody.getBytes(StandardCharsets.UTF_8));
        // Le corps servi est altéré : version changée après coup.
        String tamperedBody = manifestJson("9.9.9-evil");
        String url = serve(tamperedBody, signature);
        assertThrows(Exception.class, () -> testChannel().telecharger(url));
    }

    @Test
    void httpsOnlyChannelRefusesHttpUrl() {
        // Canal de production (HTTPS strict, comme ForgeInstaller) : HTTP refusé.
        CanalMoteur production = new CanalMoteur(publicKeyBase64);
        assertThrows(Exception.class,
                () -> production.telecharger("http://127.0.0.1:1/manifest.json"));
    }
}
