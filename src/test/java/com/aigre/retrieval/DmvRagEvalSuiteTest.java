package com.aigre.retrieval;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval ground truth for the dmv domain profile: each question's top-ranked chunk must come
 * from the NH rule chapter that actually answers it (test-data/dmv/corpus-manifest.md). Same
 * top-1 methodology as RagEvalSuiteTest. Expected files were chosen by confirming the topic's
 * text is in that chapter; where Saf-C 200 (hearings) still overlaps Saf-C 7500 (financial
 * responsibility), either is accepted.
 *
 * Only runs under the dmv profile, against a database with the DMV corpus ingested:
 *   mvn test -Dtest=DmvRagEvalSuiteTest -Dspring.profiles.active=dmv
 *     -Dspring.datasource.url=jdbc:postgresql://localhost:5434/aigre_dmv
 * Live hybrid search plus an LLM rerank per candidate, so it takes minutes.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = "dmv")
class DmvRagEvalSuiteTest {

    @Autowired
    private RetrievalService retrievalService;

    static Stream<Arguments> groundTruthCases() {
        return Stream.of(
                Arguments.of("DQ-01", "How do I get a duplicate certificate of title if mine was lost?",
                        Set.of("SAF-C-1900.txt")),
                Arguments.of("DQ-02", "How is a lien removed from a title after the loan is paid off?",
                        Set.of("SAF-C-1900.txt")),
                Arguments.of("DQ-03", "What do I need to get a title for a salvage vehicle that has been rebuilt?",
                        Set.of("SAF-C-1900.txt")),
                Arguments.of("DQ-04", "Is an odometer disclosure required when a vehicle title is transferred?",
                        Set.of("SAF-C-1900.txt")),
                Arguments.of("DQ-05", "Who is eligible for a hardship registration?",
                        Set.of("SAF-C-3000.txt")),
                Arguments.of("DQ-06", "What are the rules for vanity plates?",
                        Set.of("SAF-C-500.txt")),
                Arguments.of("DQ-07", "What are the requirements for the ignition interlock program?",
                        Set.of("SAF-C-6500.txt")),
                Arguments.of("DQ-08", "What does the commercial driver license skills test involve?",
                        Set.of("SAF-C-1800.txt")),
                Arguments.of("DQ-09", "How long do I have to keep an SR-22 proof of financial responsibility on file?",
                        Set.of("SAF-C-7500.txt", "SAF-C-200.txt")),
                Arguments.of("DQ-10", "How many points on my driving record lead to a suspension?",
                        Set.of("SAF-C-7500.txt", "SAF-C-200.txt")),
                Arguments.of("DQ-11", "Can the restoration fee be waived?",
                        Set.of("SAF-C-1500.txt")),
                Arguments.of("DQ-12", "What happens to my license if I refuse a breath test?",
                        Set.of("SAF-C-2800.txt", "SAF-C-7500.txt", "SAF-C-200.txt")),
                Arguments.of("DQ-13", "What happens when a vehicle is rejected at a state inspection?",
                        Set.of("SAF-C-3200.txt")),
                Arguments.of("DQ-14", "How are on-board diagnostic emissions tests done at inspection stations?",
                        Set.of("SAF-C-3200.txt")),
                Arguments.of("DQ-15", "What are the rules for a dealer using dealer plates?",
                        Set.of("SAF-C-2000.txt")),
                Arguments.of("DQ-16", "Who is allowed to get personal information from motor vehicle records?",
                        Set.of("SAF-C-5600.txt")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("groundTruthCases")
    void groundTruthQuestionRetrievesExpectedChapter(String evalId, String question, Set<String> expectedFileNames) {
        List<RetrievedSource> results = retrievalService.retrieve(question);

        assertThat(results).as(evalId + ": expected at least one result").isNotEmpty();
        assertThat(results.get(0).metadata().get("file_name"))
                .as(evalId + ": top result's source document")
                .isIn(expectedFileNames.toArray());
    }
}
