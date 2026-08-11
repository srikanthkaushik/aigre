package com.aigre.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the ground-truth (category A) and distractor-stress (category B)
 * questions from test-data/eval-questions.md against the live
 * RetrievalService and the real tranche-1 corpus in test-data/documents.
 *
 * This is a milestone-level eval suite, not a fast unit test: each case
 * does a live hybrid search plus one LLM rerank call per candidate
 * (initial-k of them), so the full suite takes real minutes, not seconds.
 * Run it deliberately: mvn test -Dtest=RagEvalSuiteTest
 *
 * Requires the corpus to already be ingested (POST /ingest/reset?confirm=true)
 * and the live aigre-pg + Ollama stack running -- this suite does NOT reset
 * the corpus itself, since re-ingesting 38 documents on every run would make
 * an already-slow suite far slower. If corpus questions start failing,
 * re-ingest first before assuming a regression.
 */
@SpringBootTest
class RagEvalSuiteTest {

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @BeforeAll
    static void requireCorpusIngested() {
        // Sanity guard rather than a hard dependency: if test-data/documents
        // is missing entirely, fail fast with a clear message instead of a
        // confusing wall of "wrong document retrieved" failures.
        assertThat(Files.isDirectory(Path.of("test-data/documents")))
                .as("test-data/documents must exist -- run from the project root")
                .isTrue();
    }

    // -------------------- Category A: ground truth --------------------

    static Stream<Arguments> groundTruthCases() {
        return Stream.of(
                Arguments.of(
                        "EQ-001",
                        "How long does DOT have to repair a reported pothole once it's submitted?",
                        Set.of("road-maintenance-sop-v2-current.txt")),
                Arguments.of(
                        "EQ-002",
                        "Who repairs a traffic signal that's stuck on red at an intersection?",
                        Set.of("traffic-signal-policy.txt")),
                Arguments.of(
                        "EQ-003",
                        "How do I report a city bus that consistently skips my stop?",
                        Set.of("public-transit-faq.txt")),
                Arguments.of(
                        "EQ-005",
                        "Who do I contact if a street light has been out for two weeks?",
                        Set.of("street-lighting-policy.txt")),
                Arguments.of(
                        "EQ-006",
                        "How long does the city have to respond to a water main break?",
                        Set.of("water-main-break-sop.txt")),
                Arguments.of(
                        "EQ-007",
                        "What happens if my trash isn't picked up on the scheduled day?",
                        // FIXED (see PROJECT.md "cross-reference-competition"): DEP's
                        // illegal-dumping-policy.txt used to win here because its own
                        // disambiguation text ("that's a DPW matter, NOT illegal dumping")
                        // scored highly against this query. Fixed at the corpus level --
                        // authors wrap that clause in [[XREF]]...[[/XREF]] and
                        // CorpusIngestionService excludes marked spans from both the embedded
                        // vector and the rerank-scoring text (a "rerank_text" metadata field),
                        // while the full original prose is still returned/shown.
                        Set.of("trash-collection-sop.txt")),
                Arguments.of(
                        "EQ-008",
                        "Which streets get priority during a snowstorm?",
                        // Newly observed failing (DOT's winter-road-treatment-sop.txt wins
                        // instead) while verifying the cross-reference-competition fix above.
                        // Investigated, not assumed: reverting the one corpus edit made to
                        // that file reproduced the identical failure, so this isn't caused by
                        // the fix -- most likely the same LLM-rerank sampling variance
                        // documented elsewhere in this project (PROJECT.md). Left as a known
                        // finding rather than relaxed into the accepted set.
                        Set.of("snow-removal-policy.txt")),
                Arguments.of(
                        "EQ-009",
                        "What's the process for reporting suspected child neglect through the grievance portal?",
                        Set.of("mandatory-reporting-guide.txt")),
                Arguments.of(
                        "EQ-010",
                        "How does the city investigate a complaint about unsanitary conditions at a restaurant?",
                        // FIXED (cross-reference-competition, see EQ-007): DHHS's
                        // public-health-nuisance-inspection-sop.txt explicitly said "a pest
                        // complaint about a RESTAURANT... goes to Food Safety SOP instead" --
                        // that disambiguation sentence used to compete with the real answer.
                        // Now marked [[XREF]] and excluded from embedding/rerank scoring.
                        Set.of("food-safety-sop.txt")),
                Arguments.of(
                        "EQ-011",
                        "How do I appeal a denied benefits eligibility decision?",
                        // Newly observed failing (DOE's free-reduced-lunch-faq.txt wins
                        // instead) while verifying the cross-reference-competition fix above.
                        // Not new, on closer look: PROJECT.md already recorded this exact case
                        // flipping during an earlier, unrelated experiment (a reverted rerank-
                        // prompt tweak), described there as "a closely-related same-department
                        // FAQ won... arguably a legitimate alternate answer... not clearly a
                        // misretrieval." Reverting this fix's one relevant corpus edit
                        // reproduced the identical failure, confirming it isn't caused by this
                        // change. Left as a known finding rather than relaxed into the accepted
                        // set.
                        Set.of("benefits-eligibility-faq.txt")),
                Arguments.of(
                        "EQ-013",
                        "Can DOE investigate a bullying complaint if the incident happened off school grounds?",
                        Set.of("student-safety-antibullying-policy.txt")),
                Arguments.of(
                        "EQ-014",
                        "Who's responsible for fixing a broken HVAC system in a public school building?",
                        // FIXED (cross-reference-competition, see EQ-007): DPW's
                        // facilities-maintenance-policy.txt said "distinct from DOE School
                        // Facilities... school buildings are DOE's, not DPW's" -- namedropped
                        // the correct answer's topic while explaining it's NOT DPW's. Now
                        // marked [[XREF]] and excluded from embedding/rerank scoring.
                        Set.of("school-facilities-sop.txt")),
                Arguments.of(
                        "EQ-015",
                        "How do I request a special education services evaluation for my child?",
                        Set.of("special-education-faq.txt")),
                Arguments.of(
                        "EQ-016",
                        "What are my rights if my landlord hasn't fixed a heating outage in a city-subsidized unit?",
                        // Both DHUD docs legitimately cover this (the utility guide has the
                        // detailed heat/hot-water procedure the code-enforcement guide
                        // defers to) -- accepting either is correct, not a relaxed bar.
                        Set.of("tenant-complaint-code-enforcement-guide.txt", "tenant-utility-complaint-guide.txt")),
                Arguments.of(
                        "EQ-017",
                        "How long does public housing maintenance have to fix a broken elevator in a city housing complex?",
                        // SHARED/sla-policy-summary.txt namedrops "elevator outages" as a
                        // HIGH-priority example, which legitimately competes with the DHUD
                        // SOP's detailed procedure -- both accepted, not a bug.
                        // A THIRD competitor showed up in tranche 2 and is now FIXED
                        // (cross-reference-competition, see EQ-007): DOE's
                        // accessibility-ada-policy.txt said "same standard as an elevator
                        // outage in DHUD public housing" -- a genuine misretrieval (DOE's own
                        // doc, not DHUD's). Now marked [[XREF]] and excluded from
                        // embedding/rerank scoring.
                        Set.of("public-housing-maintenance-sop.txt", "sla-policy-summary.txt")),
                Arguments.of(
                        "EQ-018",
                        "How do I get an emergency shelter referral if I'm about to be evicted?",
                        Set.of("homelessness-services-faq.txt")),
                Arguments.of(
                        "EQ-019",
                        "How does the city decide if a noise complaint is DEP's jurisdiction or a police matter?",
                        Set.of("noise-ordinance-policy.txt")),
                Arguments.of(
                        "EQ-020",
                        "What counts as illegal dumping versus an ordinary missed trash pickup?",
                        // Newly observed failing (a DEP resolved-case log wins instead) while
                        // verifying the cross-reference-competition fix (see EQ-007). This is a
                        // DIFFERENT, already-documented failure mode -- a concrete resolved-case
                        // narrative outranking abstract policy prose -- same family as EQ-024,
                        // EQ-061, EQ-062's history. Not caused by this fix: the file that wins
                        // here (a resolved-cases-*.txt log) was never touched by it. Left as a
                        // known finding rather than relaxed into the accepted set.
                        Set.of("illegal-dumping-policy.txt")),
                Arguments.of(
                        "EQ-021",
                        "How do I report a factory that seems to be releasing bad-smelling smoke?",
                        Set.of("air-quality-sop.txt")),
                Arguments.of(
                        "EQ-052",
                        "A tree on the city right-of-way outside my house looks diseased and might need to come down",
                        // eval-questions.md calls this "genuinely close pair, either is
                        // defensible" from the start -- DEP determines protected status,
                        // DPW performs hazard assessment/maintenance; a right-of-way tree
                        // report can legitimately land on either doc.
                        Set.of("tree-preservation-policy.txt", "urban-forestry-policy.txt")),
                Arguments.of(
                        "EQ-053",
                        "I want to know who handles outreach for people sleeping in the park, is that the city's "
                                + "health department or housing?",
                        // eval-questions.md calls this a legitimate overlap (DHHS health
                        // angle vs. DHUD housing angle, coordinated per the new
                        // Multi-Department Coordination Protocol) -- not a distractor.
                        Set.of("homeless-outreach-coordination-guide.txt", "homelessness-services-faq.txt")),
                Arguments.of(
                        "EQ-061",
                        "There's construction dust covering everyone's cars on the whole block, it's been going on "
                                + "for a week",
                        // A DEP resolved-case log wins instead of the policy doc -- the same
                        // "concrete narrative beats abstract policy prose" failure mode as
                        // EQ-020/EQ-024/EQ-062's history, not cross-reference-competition (see
                        // EQ-007) and not affected by that fix. Left as a known finding.
                        Set.of("construction-dust-erosion-sop.txt")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("groundTruthCases")
    void groundTruthQuestionRetrievesExpectedDocument(String evalId, String question, Set<String> expectedFileNames) {
        List<RetrievedSource> results = retrievalService.retrieve(question);

        assertThat(results).as(evalId + ": expected at least one result").isNotEmpty();
        assertThat(results.get(0).metadata().get("file_name"))
                .as(evalId + ": top result should be one of " + expectedFileNames)
                .isIn(expectedFileNames);
    }

    // -------------------- Category B: distractor-stress --------------------

    static Stream<Arguments> distractorCases() {
        return Stream.of(
                Arguments.of(
                        "EQ-022",
                        "My sidewalk is cracked and someone tripped -- who's responsible for repairs?",
                        Set.of("sidewalk-maintenance-policy.txt"),
                        "road-maintenance-sop-v2-current.txt"),
                Arguments.of(
                        "EQ-023",
                        "There's no hot water in my apartment building",
                        // Observed flaky between two legitimate same-department DHUD docs
                        // (utility-specific vs. general code-enforcement guide) across
                        // repeated runs -- same class of issue as EQ-016/EQ-017. The
                        // distractor itself (a different department entirely) was
                        // correctly avoided in both observed runs.
                        Set.of("tenant-utility-complaint-guide.txt", "tenant-complaint-code-enforcement-guide.txt"),
                        "water-main-break-sop.txt"),
                Arguments.of(
                        "EQ-024",
                        "Someone is dumping construction debris in the empty lot next door",
                        // The cross-reference-competition part of this case is FIXED (see
                        // EQ-007): the named distractor, trash-collection-sop.txt, no longer
                        // wins -- confirmed it doesn't even place in the top 5 anymore. Left
                        // strict rather than passing, though: a DIFFERENT DEP resolved-case log
                        // document now wins instead, the same "concrete narrative beats abstract
                        // policy prose" failure mode as EQ-020/EQ-061/EQ-062's history, a
                        // separate, already-documented finding this fix doesn't target.
                        Set.of("illegal-dumping-policy.txt"),
                        "trash-collection-sop.txt"),
                Arguments.of(
                        "EQ-026",
                        "My street hasn't been plowed in two days and I can't get my car out",
                        Set.of("snow-removal-policy.txt"),
                        "road-maintenance-sop-v2-current.txt"),
                Arguments.of(
                        "EQ-029",
                        "There's mold in my public housing unit and my kid has asthma",
                        Set.of("public-housing-maintenance-sop.txt"),
                        "air-quality-sop.txt"),
                Arguments.of(
                        "EQ-051",
                        "My drinking water has tasted metallic for the past few days, who do I report that to?",
                        Set.of("water-quality-testing-sop.txt"),
                        "water-main-break-sop.txt"),
                Arguments.of(
                        "EQ-054",
                        "Does my kid still get free lunch if we're not on other city assistance programs?",
                        Set.of("free-reduced-lunch-faq.txt"),
                        "benefits-eligibility-faq.txt"),
                Arguments.of(
                        "EQ-055",
                        "Who do I talk to about getting my recycling bin size changed?",
                        Set.of("recycling-composting-program-faq.txt"),
                        "trash-collection-sop.txt"),
                Arguments.of(
                        "EQ-062",
                        "My son's teacher has been saying demeaning things to him in front of the class",
                        // FIXED (cross-reference-competition, see EQ-007): DOE's
                        // teacher-conduct-complaint-policy.txt said "distinct from... the
                        // anti-bullying policy" -- that clause used to let a DOE resolved-case
                        // log win instead of either named doc. Now marked [[XREF]] and excluded
                        // from embedding/rerank scoring.
                        Set.of("teacher-conduct-complaint-policy.txt"),
                        "student-safety-antibullying-policy.txt"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("distractorCases")
    void distractorQuestionPrefersCorrectDocOverNearMiss(
            String evalId, String question, Set<String> expectedFileNames, String distractorFileName) {
        List<RetrievedSource> results = retrievalService.retrieve(question);

        assertThat(results).as(evalId + ": expected at least one result").isNotEmpty();
        assertThat(results.get(0).metadata().get("file_name"))
                .as(evalId + ": top result should be one of " + expectedFileNames + ", not distractor "
                        + distractorFileName)
                .isIn(expectedFileNames);
    }

    // -------------------- Category C: superseded-version --------------------

    @org.junit.jupiter.api.Test
    void currentSlaQuestionCitesV2NotSupersededV1() {
        List<RetrievedSource> results =
                retrievalService.retrieve("What is the current SLA for DOT pothole repairs?");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).metadata())
                .as("EQ-031: must cite the current v2 SOP, not the superseded v1")
                .containsEntry("file_name", "road-maintenance-sop-v2-current.txt");
    }

    @org.junit.jupiter.api.Test
    void currentBenefitsAppealWindowQuestionCitesV2NotSupersededV1() {
        List<RetrievedSource> results =
                retrievalService.retrieve("What is the current appeal window for a denied city benefits decision?");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).metadata())
                .as("EQ-056: must cite the current v2 benefits appeals policy, not the superseded v1")
                .containsEntry("file_name", "benefits-appeals-policy-v2-current.txt");
    }

    @org.junit.jupiter.api.Test
    void historicalBenefitsAppealWindowQuestionCitesSupersededV1() {
        // Same known gap as EQ-031: reranking has no "current vs superseded"
        // awareness, so an explicitly historical framing ("back in 2023") isn't
        // guaranteed to surface the superseded doc over the current one. Included
        // to measure the gap, not assumed to pass.
        List<RetrievedSource> results = retrievalService.retrieve(
                "If I was denied benefits back in 2023, what appeal window applied then?");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).metadata())
                .as("EQ-057: historical framing should surface the superseded v1 policy")
                .containsEntry("file_name", "benefits-appeals-policy-v1-superseded.txt");
    }
}
