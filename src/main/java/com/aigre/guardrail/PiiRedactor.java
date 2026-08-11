package com.aigre.guardrail;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure regex-based redaction for PII incidentally typed into citizen free text (a grievance's
 * complaint, or a clarification follow-up) -- distinct from the structured citizenEmail/
 * citizenPhone contact fields, which are legitimate and never touched.
 *
 * Patterns are deliberately literal/narrow (exact SSN and US-phone digit groupings) rather than
 * a general PII-detection model: false negatives on unusual formats are an accepted tradeoff for
 * zero false positives on ordinary complaint text (a street address like "1234 Main St" should
 * never be redacted). See plan.md eval question #13 and test-data/grievances/eval-complaints.jsonl
 * (GRV-074..077, expected_redaction: true) for the ground truth this was built against.
 */
@Component
public class PiiRedactor {

    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("\\b\\d{3}-\\d{3}-\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");

    public record Result(String text, List<String> redactedTypes) {
    }

    public Result redact(String text) {
        if (text == null || text.isBlank()) {
            return new Result(text, List.of());
        }
        List<String> types = new ArrayList<>();
        String result = text;
        result = replaceIfMatched(result, SSN, "[REDACTED-SSN]", "SSN", types);
        result = replaceIfMatched(result, CREDIT_CARD, "[REDACTED-CARD]", "CREDIT_CARD", types);
        result = replaceIfMatched(result, PHONE, "[REDACTED-PHONE]", "PHONE", types);
        result = replaceIfMatched(result, EMAIL, "[REDACTED-EMAIL]", "EMAIL", types);
        return new Result(result, types);
    }

    private static String replaceIfMatched(
            String text, Pattern pattern, String replacement, String typeLabel, List<String> types) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        types.add(typeLabel);
        return matcher.replaceAll(Matcher.quoteReplacement(replacement));
    }
}
