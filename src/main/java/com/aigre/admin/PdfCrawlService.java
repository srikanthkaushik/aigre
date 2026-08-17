package com.aigre.admin;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The first outbound HTTP client in this codebase -- no existing WebClient/RestClient pattern to
 * reuse. Fetches a public HTML index page and extracts links to PDF documents, for the
 * department-onboarding flow (DepartmentOnboardingService).
 */
@Service
public class PdfCrawlService {

    private static final Logger log = LoggerFactory.getLogger(PdfCrawlService.class);

    private final WebClient webClient;

    public PdfCrawlService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * jsoup's absUrl("href") resolves relative hrefs against the page's own URL (passed as
     * Jsoup.parse's baseUri) -- no manual java.net.URI.resolve needed. Best-effort: a real page
     * that links a PDF via a redirect-y URL with no ".pdf" in it (e.g. "/download?doc=42") would
     * be silently missed, not downloaded-and-rejected -- known limitation, see PROJECT.md.
     */
    public List<String> findPdfLinks(String pageUrl) {
        String html = webClient.get()
                .uri(pageUrl)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        if (html == null) {
            return List.of();
        }

        Document doc = Jsoup.parse(html, pageUrl);
        return doc.select("a[href]").stream()
                .map(el -> el.absUrl("href"))
                .filter(url -> !url.isBlank())
                .filter(this::looksLikePdf)
                .distinct()
                .toList();
    }

    private boolean looksLikePdf(String url) {
        return url.toLowerCase(Locale.ROOT).contains(".pdf");
    }

    /**
     * Returns the downloaded bytes, or null (logged, never thrown) on any failure -- a dead link
     * or a non-PDF response is expected on a real-world index page and should be skipped, not
     * fail the whole onboarding call.
     */
    public byte[] download(String pdfUrl) {
        try {
            return webClient.get()
                    .uri(pdfUrl)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            log.info("Skipping {} -- HTTP {}", pdfUrl, response.statusCode());
                            return Mono.<byte[]>empty();
                        }
                        MediaType contentType = response.headers().contentType().orElse(null);
                        boolean declaredPdf = contentType != null && MediaType.APPLICATION_PDF.includes(contentType);
                        if (!declaredPdf && !looksLikePdf(pdfUrl)) {
                            log.info("Skipping {} -- not a PDF (Content-Type: {})", pdfUrl, contentType);
                            return Mono.<byte[]>empty();
                        }
                        return response.bodyToMono(byte[].class);
                    })
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (RuntimeException e) {
            log.info("Skipping {} -- {}", pdfUrl, e.getMessage());
            return null;
        }
    }
}
