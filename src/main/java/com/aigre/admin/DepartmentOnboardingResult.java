package com.aigre.admin;

import com.aigre.ingestion.IngestionSummary;

import java.util.List;

public record DepartmentOnboardingResult(
        String departmentId, int pdfsDownloaded, List<String> skippedLinks, IngestionSummary ingestionSummary) {
}
