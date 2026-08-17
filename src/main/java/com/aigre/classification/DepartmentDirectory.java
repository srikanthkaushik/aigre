package com.aigre.classification;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-memory cache of the classifier prompt's DEPARTMENTS bullet section, built from the
 * departments table. Not queried per classify() call (that's a hot-ish path, once per grievance
 * submission) -- built once at startup and rebuilt only when refresh() is called explicitly, by
 * the department-onboarding flow after inserting a new department row.
 *
 * @DependsOnDatabaseInitialization ensures schema.sql's seed INSERT/UPDATE has already run before
 * this reads the departments table -- the first component in this codebase doing a DB read from
 * its constructor, so this ordering matters in a way nothing else here has needed before.
 *
 * The generated bullet text doesn't word-wrap at ~78 columns the way the original hardcoded
 * prompt did for source-file readability -- that's whitespace-only, the LLM doesn't see source
 * line breaks as meaningful, so it has zero effect on classification.
 */
@Component
@DependsOnDatabaseInitialization
public class DepartmentDirectory {

    private final NamedParameterJdbcTemplate jdbc;
    private volatile String departmentsPromptSection;

    public DepartmentDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        refresh();
    }

    public String departmentsPromptSection() {
        return departmentsPromptSection;
    }

    public synchronized void refresh() {
        List<String> bullets = jdbc.query(
                "SELECT id, short_name, jurisdiction_notes FROM departments ORDER BY id",
                EmptySqlParameterSource.INSTANCE,
                (rs, rowNum) -> "- %s (%s): %s".formatted(
                        rs.getString("id"), rs.getString("short_name"), rs.getString("jurisdiction_notes")));
        this.departmentsPromptSection = String.join("\n", bullets);
    }
}
