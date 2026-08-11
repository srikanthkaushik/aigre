package com.aigre.classification;

import com.aigre.intake.GrievanceIntakeRequest;
import com.aigre.intake.GrievanceIntakeResponse;
import com.aigre.intake.GrievanceIntakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every complaint in test-data/grievances/eval-complaints.jsonl through
 * the real intake pipeline and reports department-match accuracy against
 * ground truth. Originally measured against PlaceholderClassifier (a keyword
 * stub, 40.7% baseline); now measures LlmGrievanceClassifier.
 *
 * Four consecutive live runs against qwen2.5:7b via Ollama measured 65.9%,
 * 86.8%, 74.7% -- confirmed via direct debugging to be genuine LLM sampling
 * variance, not a code bug (the same input classified correctly in
 * isolation immediately after scoring "wrong" in a harness run). One run
 * against Claude Sonnet 5 via Anthropic (same code, same prompt, just
 * llm.provider=anthropic) measured 87/91 (95.6%), with all 4 remaining
 * mismatches being genuinely defensible close calls rather than the
 * systematic misses Ollama showed. Treat any single Ollama run's percentage
 * as a noisy sample, not a precise number -- see PROJECT.md for the full
 * investigation, including two real bugs found and fixed along the way
 * (unquoted-enum JSON, and the LLM emitting the string "null" instead of
 * the JSON null literal).
 *
 * The department-accuracy floor asserted below is a REGRESSION floor, set
 * with margin below the observed low end of the Ollama variance range (the
 * default provider) so the assertion catches a real regression without
 * being flaky against normal sampling noise.
 *
 * Side effect: each run submits all 91 complaints through the real intake
 * pipeline, writing real citizen/grievance/status_history rows to aigre-pg.
 * Fine for a local dev database; not something to point at a shared one.
 */
@SpringBootTest
class ComplaintEvalHarnessTest {

    private static final Path EVAL_FILE = Path.of("test-data/grievances/eval-complaints.jsonl");

    @Autowired
    private GrievanceIntakeService intakeService;

    @Autowired
    private ObjectMapper objectMapper;

    private record EvalCase(String id, String rawText, Set<String> expectedDepartments, int expectedScenario) {
    }

    @Test
    void reportDepartmentAccuracyAgainstLabeledComplaintSet() throws IOException {
        List<EvalCase> cases = loadEvalCases();
        assertThat(cases).as("eval-complaints.jsonl should have loaded rows").isNotEmpty();

        int correct = 0;
        List<String> mismatches = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            GrievanceIntakeResponse response =
                    intakeService.submit(new GrievanceIntakeRequest(evalCase.rawText(), null, null, null));

            boolean expectsNoDepartment = evalCase.expectedDepartments().isEmpty();
            boolean predictedNoDepartment = response.departmentPredicted() == null;

            boolean isCorrect = expectsNoDepartment
                    ? predictedNoDepartment
                    : evalCase.expectedDepartments().contains(response.departmentPredicted());

            if (isCorrect) {
                correct++;
            } else {
                mismatches.add("%s (scenario %d): expected %s, got %s"
                        .formatted(
                                evalCase.id(),
                                evalCase.expectedScenario(),
                                evalCase.expectedDepartments(),
                                response.departmentPredicted()));
            }
        }

        double accuracy = (double) correct / cases.size();
        System.out.printf(
                "%nComplaint eval harness: %d/%d correct (%.1f%%) department-match against LlmGrievanceClassifier%n",
                correct, cases.size(), accuracy * 100);
        if (!mismatches.isEmpty()) {
            System.out.println("Mismatches:");
            mismatches.forEach(m -> System.out.println("  " + m));
        }

        // Regression floor, not a target. Observed range across 4 live runs of the real
        // classifier: 65.9%-86.8% (genuine LLM sampling variance, not flaky test infra -- see
        // class javadoc). Floor set with margin below the observed low end so this catches a
        // real regression (e.g. a broken prompt or provider swap gone wrong) without tripping
        // on normal sampling noise.
        assertThat(accuracy).isGreaterThanOrEqualTo(0.55);
    }

    private List<EvalCase> loadEvalCases() throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        for (String line : Files.readAllLines(EVAL_FILE)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(line);
            Set<String> departments = new HashSet<>();
            node.get("expected_departments").forEach(d -> departments.add(d.asString()));
            cases.add(new EvalCase(
                    node.get("id").asString(),
                    node.get("raw_text").asString(),
                    departments,
                    node.get("expected_scenario").asInt()));
        }
        return cases;
    }
}
