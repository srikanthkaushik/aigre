package com.aigre.intake;

import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Mints "G0001"-style grievance IDs from the grievance_id_seq Postgres sequence
 * (see schema.sql). Both GrievanceIntakeService and GrievanceWorkflowService share this
 * single generator so the two intake paths draw from one counter, not two.
 */
@Component
public class GrievanceIdGenerator {

    private final NamedParameterJdbcTemplate jdbc;

    public GrievanceIdGenerator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next() {
        Long n = jdbc.queryForObject(
                "SELECT nextval('grievance_id_seq')", EmptySqlParameterSource.INSTANCE, Long.class);
        return "G%04d".formatted(n);
    }
}
