package jo.joide.diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistance des rapports de plantage (portage CodeIDE §5.8, ADR-021).
 *
 * <p>Format : un fichier JSON par rapport dans {@code filesDir/crashes/},
 * nommé {@code <horodatage>-<id>.json} — le préfixe rend le tri
 * lexicographique chronologique (13 chiffres jusqu'en 2286). L'état
 * « consulté » vit dans un <b>fichier témoin</b> à côté du rapport
 * ({@code <horodatage>-<id>.reviewed}) : un rapport est une pièce
 * d'horodatage, on ne le réécrit jamais.</p>
 *
 * <p>Garanties : écriture <b>synchrone et atomique</b> ({@code .tmp} puis
 * renommage) ; taille maximale de
 * {@link CrashLimits#MAX_REPORT_BYTES} par rapport — la réduction
 * progressive ({@link CrashReportJson.Reduction}) coupe d'abord les filons,
 * puis la trace ; au plus {@link CrashLimits#MAX_REPORTS} rapports conservés,
 * les plus récents.</p>
 */
public final class CrashReportStore {

    /** Nom d'un rapport : {@code <horodatage numérique>-<identifiant>.json}. */
    private static final Pattern REPORT_NAME = Pattern.compile("^\\d+-(.+)\\.json$");

    private static final String TMP_SUFFIX = ".tmp";

    private final Path directory;

    public CrashReportStore(Path directory) {
        this.directory = directory;
    }

    /**
     * Enregistre un rapport : réduction éventuelle, écriture atomique, puis
     * rétention.
     *
     * @param report le rapport à persister
     * @throws IOException si l'écriture échoue (le gestionnaire appelle dans
     *         son try/catch global)
     */
    public void save(CrashReport report) throws IOException {
        Files.createDirectories(directory);

        CrashReportJson.Reduction level = CrashReportJson.Reduction.FULL;
        byte[] bytes;
        while (true) {
            bytes = Json.write(CrashReportJson.write(report, level))
                    .getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= CrashLimits.MAX_REPORT_BYTES || level == CrashReportJson.Reduction.MINIMAL) {
                break;
            }
            level = level.next();
        }

        Path target = fileOf(report.id(), report.timestampMillis());
        Path temporary = directory.resolve(target.getFileName() + TMP_SUFFIX);
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exotic) {
            // Renommage atomique impossible (système de fichiers exotique) :
            // copie directe, dernier recours avant l'échec.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(temporary);
        }
        applyRetention();
    }

    /**
     * Lit un rapport complet.
     *
     * @param id identifiant du rapport
     * @return le rapport, ou {@code null} s'il n'existe plus ou est corrompu
     */
    public CrashReport get(String id) {
        Path file = existingFile(id);
        return file == null ? null : readReport(file);
    }

    /**
     * Liste les résumés, du plus récent au plus ancien. Les fichiers
     * corrompus sont ignorés (comptés, jamais fatals).
     */
    public List<CrashReportSummary> listSummaries() {
        List<CrashReportSummary> summaries = new ArrayList<>();
        for (Path file : sortedReports()) {
            String text = readFile(file);
            if (text == null) {
                continue;
            }
            CrashReportSummary summary =
                    CrashReportJson.readSummary(text, isReviewed(file));
            if (summary != null) {
                summaries.add(summary);
            }
        }
        return summaries;
    }

    /**
     * Marque un rapport comme consulté (pose le fichier témoin).
     *
     * @param id identifiant du rapport
     * @return {@code true} si le témoin a été posé sur un rapport existant
     */
    public boolean markReviewed(String id) {
        Path file = existingFile(id);
        if (file == null) {
            return false;
        }
        try {
            Path witness = witnessOf(file);
            Files.createDirectories(witness.getParent());
            if (!Files.exists(witness)) {
                Files.createFile(witness);
            }
            return Files.exists(witness);
        } catch (IOException witnessFailed) {
            return false;
        }
    }

    /**
     * Supprime un rapport et son éventuel témoin.
     *
     * @param id identifiant du rapport
     * @return {@code true} si le rapport a été supprimé
     */
    public boolean delete(String id) {
        Path file = existingFile(id);
        if (file == null) {
            return false;
        }
        deleteQuietly(witnessOf(file));
        return deleteQuietly(file);
    }

    /**
     * Supprime tous les rapports, témoins et restes temporaires.
     *
     * @return le nombre de rapports supprimés
     */
    public int deleteAll() {
        List<Path> reports = sortedReports();
        for (Path file : reports) {
            deleteQuietly(witnessOf(file));
            deleteQuietly(file);
        }
        cleanLeftovers();
        return reports.size();
    }

    /**
     * Indique s'il existe un rapport non consulté — sert à signaler au
     * démarrage (écran de diagnostic) qu'un plantage attend une lecture.
     */
    public boolean hasUnreviewed() {
        for (Path file : sortedReports()) {
            if (!isReviewed(file)) {
                return true;
            }
        }
        return false;
    }

    // ---- Internes -----------------------------------------------------------------

    /** Chemin du fichier d'un rapport (création du nom, existence non requise). */
    private Path fileOf(String id, long timestampMillis) {
        return directory.resolve(timestampMillis + "-" + id + CrashLimits.REPORT_SUFFIX);
    }

    /**
     * Fichier existant d'un rapport, par identifiant. L'identifiant est
     * <b>retranché du nom</b> plutôt que cherché par suffixe : un suffixe est
     * ambigu quand un identifiant se termine par celui d'un autre — le témoin
     * serait alors posé sur le mauvais rapport.
     */
    private Path existingFile(String id) {
        for (Path file : sortedReports()) {
            if (id.equals(reportId(file))) {
                return file;
            }
        }
        return null;
    }

    /** Rapports conservés, du plus récent au plus ancien. */
    private List<Path> sortedReports() {
        List<Path> reports = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return reports;
        }
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
            for (Path file : listing) {
                if (Files.isRegularFile(file)
                        && file.getFileName().toString().endsWith(CrashLimits.REPORT_SUFFIX)) {
                    reports.add(file);
                }
            }
        } catch (IOException unreadable) {
            return reports;
        }
        reports.sort(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed());
        return reports;
    }

    /** Identifiant porté par un fichier de rapport, ou {@code null} si mal nommé. */
    private static String reportId(Path file) {
        Matcher matcher = REPORT_NAME.matcher(file.getFileName().toString());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Témoin « consulté » associé à un fichier de rapport. */
    private static Path witnessOf(Path file) {
        return file.resolveSibling(file.getFileName() + CrashLimits.REVIEWED_SUFFIX);
    }

    /** Le rapport associé à ce fichier a-t-il été consulté ? */
    private static boolean isReviewed(Path file) {
        return Files.isRegularFile(witnessOf(file));
    }

    /**
     * Rétention : au plus {@link CrashLimits#MAX_REPORTS} rapports, les plus
     * récents — les témoins orphelins et fichiers temporaires partent avec.
     */
    private void applyRetention() {
        List<Path> reports = sortedReports();
        if (reports.size() > CrashLimits.MAX_REPORTS) {
            for (Path file : reports.subList(CrashLimits.MAX_REPORTS, reports.size())) {
                deleteQuietly(witnessOf(file));
                deleteQuietly(file);
            }
        }
        cleanLeftovers();
    }

    /** Supprime les fichiers temporaires et les témoins sans rapport. */
    private void cleanLeftovers() {
        Set<String> reportNames = new HashSet<>();
        for (Path file : sortedReports()) {
            reportNames.add(file.getFileName().toString());
        }
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
            for (Path file : listing) {
                String name = file.getFileName().toString();
                boolean temporaryLeftover = name.endsWith(TMP_SUFFIX);
                boolean orphanWitness = name.endsWith(CrashLimits.REVIEWED_SUFFIX)
                        && !reportNames.contains(name.substring(0,
                                name.length() - CrashLimits.REVIEWED_SUFFIX.length())
                                + CrashLimits.REPORT_SUFFIX);
                if (temporaryLeftover || orphanWitness) {
                    deleteQuietly(file);
                }
            }
        } catch (IOException unreadable) {
            // Nettoyage au mieux : le prochain passage retentera.
        }
    }

    private static CrashReport readReport(Path file) {
        String text = readFile(file);
        return text == null ? null : CrashReportJson.read(text);
    }

    /** Lit un fichier en texte, ou {@code null} s'il est illisible. */
    private static String readFile(Path file) {
        // readAllBytes, pas readString : la règle API 28 du dépôt proscrit
        // Files.readString (API 33, sans désucrage — ADR-009).
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            return false;
        }
    }
}
