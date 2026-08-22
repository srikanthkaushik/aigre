package com.aigre.embed;

import com.aigre.admin.InvalidDepartmentRequestException;
import com.aigre.classification.DepartmentDirectory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Admin-managed allowlist of origins (scheme://host[:port], no path) a department's embedded
 * chat widget (frontend/public/embed.js, GET /embed/chat) is permitted to run from -- read by
 * EmbedChatController to build a dynamic Content-Security-Policy: frame-ancestors header per
 * department. Enforcement happens in the browser against that header, not here; this is just
 * the source of truth it's built from.
 */
@Service
public class EmbedOriginService {

    private static final Pattern VALID_ORIGIN = Pattern.compile("^https?://[^/]+$");

    private final NamedParameterJdbcTemplate jdbc;
    private final DepartmentDirectory departmentDirectory;

    public EmbedOriginService(NamedParameterJdbcTemplate jdbc, DepartmentDirectory departmentDirectory) {
        this.jdbc = jdbc;
        this.departmentDirectory = departmentDirectory;
    }

    public List<String> listOrigins(String departmentId) {
        return jdbc.query(
                "SELECT origin FROM department_embed_origins WHERE department_id = :id ORDER BY origin",
                new MapSqlParameterSource("id", departmentId),
                (rs, rowNum) -> rs.getString("origin"));
    }

    public void addOrigin(String departmentId, String origin) {
        requireValidDepartment(departmentId);
        if (!VALID_ORIGIN.matcher(origin).matches()) {
            throw new InvalidDepartmentRequestException(
                    "origin must be a bare scheme://host[:port] with no path, e.g. 'https://dmv.state.nh.us' -- got '"
                            + origin + "'");
        }
        jdbc.update(
                "INSERT INTO department_embed_origins (department_id, origin) VALUES (:id, :origin) "
                        + "ON CONFLICT DO NOTHING",
                new MapSqlParameterSource().addValue("id", departmentId).addValue("origin", origin));
    }

    public void removeOrigin(String departmentId, String origin) {
        requireValidDepartment(departmentId);
        jdbc.update(
                "DELETE FROM department_embed_origins WHERE department_id = :id AND origin = :origin",
                new MapSqlParameterSource().addValue("id", departmentId).addValue("origin", origin));
    }

    private void requireValidDepartment(String departmentId) {
        if (!departmentDirectory.departmentIds().contains(departmentId)) {
            throw new InvalidDepartmentRequestException("Unknown department: " + departmentId);
        }
    }
}
