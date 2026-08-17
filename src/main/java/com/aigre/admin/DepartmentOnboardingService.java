package com.aigre.admin;

import com.aigre.classification.DepartmentDirectory;
import com.aigre.ingestion.CorpusIngestionService;
import com.aigre.ingestion.IngestionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Onboards a brand-new city department: crawls a public URL for PDF policy documents, downloads
 * them into the RAG corpus, inserts the department row, and refreshes the classifier's live
 * DEPARTMENTS prompt section -- the whole point being that department #2 onward needs zero code
 * change or redeploy. Employee/staff provisioning is deliberately out of scope: a freshly
 * onboarded department has zero department_employees rows, so a grievance routed there has no one
 * to claim it until staff are added separately.
 */
@Service
public class DepartmentOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentOnboardingService.class);
    private static final Pattern VALID_ID = Pattern.compile("^[A-Z]{2,10}$");

    private final PdfCrawlService pdfCrawlService;
    private final NamedParameterJdbcTemplate jdbc;
    private final DepartmentDirectory departmentDirectory;
    private final CorpusIngestionService corpusIngestionService;
    private final Path corpusPath;

    public DepartmentOnboardingService(
            PdfCrawlService pdfCrawlService,
            NamedParameterJdbcTemplate jdbc,
            DepartmentDirectory departmentDirectory,
            CorpusIngestionService corpusIngestionService,
            @Value("${rag.ingest.corpus-path}") String corpusPath) {
        this.pdfCrawlService = pdfCrawlService;
        this.jdbc = jdbc;
        this.departmentDirectory = departmentDirectory;
        this.corpusIngestionService = corpusIngestionService;
        this.corpusPath = Path.of(corpusPath);
    }

    public DepartmentOnboardingResult onboard(DepartmentOnboardingRequest request) {
        String id = request.id().trim().toUpperCase();
        if (!VALID_ID.matcher(id).matches()) {
            throw new InvalidDepartmentRequestException(
                    "Department id must be 2-10 uppercase letters, got '" + request.id() + "'");
        }

        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM departments WHERE id = :id)",
                new MapSqlParameterSource("id", id),
                Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            throw new IllegalStateException("Department " + id + " already exists");
        }

        List<String> pdfLinks = pdfCrawlService.findPdfLinks(request.sourceUrl());
        List<String> skippedLinks = new ArrayList<>();
        int downloaded = 0;

        Path departmentDir = corpusPath.resolve(id);
        try {
            Files.createDirectories(departmentDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (String link : pdfLinks) {
            byte[] bytes = pdfCrawlService.download(link);
            if (bytes == null) {
                skippedLinks.add(link);
                continue;
            }
            String filename = sanitizeFilename(link);
            try {
                Files.write(departmentDir.resolve(filename), bytes);
                downloaded++;
            } catch (IOException e) {
                log.warn("Failed writing downloaded PDF {} for department {}", filename, id, e);
                skippedLinks.add(link);
            }
        }

        jdbc.update(
                """
                INSERT INTO departments (id, name, short_name, jurisdiction_notes)
                VALUES (:id, :name, :shortName, :jurisdictionNotes)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", request.name())
                        .addValue("shortName", request.shortName())
                        .addValue("jurisdictionNotes", request.jurisdictionNotes()));

        departmentDirectory.refresh();

        // A cancelled client request (e.g. an aborted connection) during the download loop above
        // sets this boundedElastic worker thread's interrupt status; left uncleared, it leaks into
        // the blocking Ollama embedding calls reset() makes next and fails the very first batch
        // immediately -- since removeAll() runs before any batch is re-added, that silently wipes
        // the ENTIRE vector store (every existing department, not just this one) with nothing put
        // back. Confirmed via a real incident: rag_documents was left at 0 rows after exactly this
        // sequence. Clearing here guarantees reset() always runs on a clean thread regardless of
        // what happened earlier in this same request.
        Thread.interrupted();

        // Full wipe-and-reseed of the entire vector store, same as CorpusIngestionService's only
        // existing ingestion path -- acceptable at this corpus scale, but onboarding department N
        // briefly empties and rebuilds the RAG index for every existing department too.
        IngestionSummary ingestionSummary = corpusIngestionService.reset();

        return new DepartmentOnboardingResult(id, downloaded, skippedLinks, ingestionSummary);
    }

    /** Strips query string, path segments, and any path-traversal sequence -- keeps only the basename. */
    private static String sanitizeFilename(String url) {
        String path = URI.create(url).getPath();
        String basename = path.substring(path.lastIndexOf('/') + 1);
        String sanitized = basename.replace("..", "").replaceAll("[\\\\/]", "");
        return sanitized.isBlank() ? "document.pdf" : sanitized;
    }
}
