package com.aigre.chat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Caffeine-wrapper behavior -- no Spring context needed, so this constructs the cache directly. */
class ChatResponseCacheTest {

    private static final ChatResponseCache.CachedChatAnswer ANSWER =
            new ChatResponseCache.CachedChatAnswer("DOT has 5 business days.", List.of());

    private ChatResponseCache cache(boolean enabled) {
        return new ChatResponseCache(enabled, Duration.ofMinutes(30), 500);
    }

    @Test
    void missOnAnEmptyCache() {
        assertThat(cache(true).get("what's the pothole SLA?", "DOT")).isEmpty();
    }

    @Test
    void hitOnTheExactSameQuestionAndDepartment() {
        ChatResponseCache cache = cache(true);
        cache.put("What's the pothole SLA?", "DOT", ANSWER);

        assertThat(cache.get("What's the pothole SLA?", "DOT")).contains(ANSWER);
    }

    @Test
    void normalizationIgnoresCaseAndSurroundingOrCollapsedWhitespace() {
        ChatResponseCache cache = cache(true);
        cache.put("What's   the pothole SLA?", "DOT", ANSWER);

        assertThat(cache.get("  what's the pothole sla? ", "DOT")).contains(ANSWER);
    }

    @Test
    void differentDepartmentIsADifferentEntry() {
        ChatResponseCache cache = cache(true);
        cache.put("What's the pothole SLA?", "DOT", ANSWER);

        assertThat(cache.get("What's the pothole SLA?", "DPW")).isEmpty();
    }

    @Test
    void nullDepartmentIsItsOwnDistinctEntryFromAnySpecificDepartment() {
        ChatResponseCache cache = cache(true);
        cache.put("What's the pothole SLA?", null, ANSWER);

        assertThat(cache.get("What's the pothole SLA?", "DOT")).isEmpty();
        assertThat(cache.get("What's the pothole SLA?", null)).contains(ANSWER);
    }

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        ChatResponseCache cache = new ChatResponseCache(true, Duration.ofMillis(50), 500);
        cache.put("What's the pothole SLA?", "DOT", ANSWER);

        Thread.sleep(200);

        assertThat(cache.get("What's the pothole SLA?", "DOT")).isEmpty();
    }

    @Test
    void disabledCacheNeverStoresOrReturnsAnything() {
        ChatResponseCache cache = cache(false);
        cache.put("What's the pothole SLA?", "DOT", ANSWER);

        assertThat(cache.get("What's the pothole SLA?", "DOT")).isEmpty();
    }

    @Test
    void invalidateAllClearsEveryEntry() {
        ChatResponseCache cache = cache(true);
        cache.put("What's the pothole SLA?", "DOT", ANSWER);
        cache.put("What's the noise ordinance?", null, ANSWER);

        cache.invalidateAll();

        assertThat(cache.get("What's the pothole SLA?", "DOT")).isEmpty();
        assertThat(cache.get("What's the noise ordinance?", null)).isEmpty();
    }

    @Test
    void getOnADisabledCacheNeverThrowsEvenWhenNeverPopulated() {
        Optional<ChatResponseCache.CachedChatAnswer> result = cache(false).get("anything", "DOT");

        assertThat(result).isEmpty();
    }
}
