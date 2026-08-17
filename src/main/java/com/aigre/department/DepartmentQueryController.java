package com.aigre.department;

import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public (see SecurityConfig) -- department-name.pipe.ts renders on citizen.html, the
 * unauthenticated citizen status page, so the frontend needs this reachable without a login.
 */
@RestController
public class DepartmentQueryController {

    private final NamedParameterJdbcTemplate jdbc;

    public DepartmentQueryController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/departments")
    public List<DepartmentSummary> list() {
        return jdbc.query(
                "SELECT id, name FROM departments ORDER BY id",
                EmptySqlParameterSource.INSTANCE,
                (rs, rowNum) -> new DepartmentSummary(rs.getString("id"), rs.getString("name")));
    }
}
