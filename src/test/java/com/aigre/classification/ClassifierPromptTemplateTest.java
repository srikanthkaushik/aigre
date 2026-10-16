package com.aigre.classification;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the city classifier prompt to the exact text it had as an inline Java text block before it
 * moved to prompts/city/classifier.txt. Classification accuracy (ComplaintEvalHarnessTest) was
 * measured against that text, so any change to it should be deliberate -- update the hash here
 * and re-run the eval harness when it is.
 */
class ClassifierPromptTemplateTest {

    private static final String CITY_PROMPT_SHA256 = "5620f3aea0b50c07849c55869be5e9943b9c7201a7790e14fe6ffd4a34e52a7d";

    @Test
    void cityPromptIsUnchangedFromTheMeasuredVersion() throws Exception {
        String template = LlmGrievanceClassifier.loadPromptTemplate(new ClassPathResource("prompts/city/classifier.txt"));

        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(template.getBytes(StandardCharsets.UTF_8)));

        assertThat(sha256).isEqualTo(CITY_PROMPT_SHA256);
    }

    @Test
    void everyPromptHasExactlyTheTwoFormatSlots() {
        for (String profile : new String[] {"city", "dmv"}) {
            String template = LlmGrievanceClassifier.loadPromptTemplate(
                    new ClassPathResource("prompts/" + profile + "/classifier.txt"));

            assertThat(template.split("%s", -1)).as(profile + " prompt %s slots").hasSize(3);
            assertThat(template).as(profile + " prompt").contains("RESULT:");
        }
    }
}
