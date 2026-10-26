package org.ntrloc.graph.db.partition.security.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// security_oidc_provider. The secret column holds SecretCipher output, never plaintext.
@Component
public class OidcProviderRepository {

    private static final String PARAM_REGISTRATION_ID = "registrationId";

    public record OidcProviderRow(String registrationId, String displayName, String issuerUri, String clientId,
                                  String clientSecretEncrypted, String scopes, boolean enabled,
                                  long version, OffsetDateTime updatedAt) {}

    private static final RowMapper<OidcProviderRow> ROW = (rs, _) -> new OidcProviderRow(
            rs.getString("registration_id"),
            rs.getString("display_name"),
            rs.getString("issuer_uri"),
            rs.getString("client_id"),
            rs.getString("client_secret_encrypted"),
            rs.getString("scopes"),
            rs.getBoolean("enabled"),
            rs.getLong("version"),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbcClient;

    public OidcProviderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<OidcProviderRow> findAll() {
        return jdbcClient.sql("SELECT * FROM security_oidc_provider ORDER BY display_name").query(ROW).list();
    }

    public Optional<OidcProviderRow> find(String registrationId) {
        return jdbcClient.sql("SELECT * FROM security_oidc_provider WHERE registration_id = :registrationId")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .query(ROW)
                .optional();
    }

    public void insert(OidcProviderRow row, UUID updatedBy) {
        jdbcClient.sql("""
                        INSERT INTO security_oidc_provider (registration_id, display_name, issuer_uri, client_id,
                            client_secret_encrypted, scopes, enabled, updated_by)
                        VALUES (:registrationId, :displayName, :issuerUri, :clientId, :clientSecret, :scopes, :enabled,
                            :updatedBy)
                        """)
                .params(params(row, updatedBy))
                .update();
    }

    // version and updated_at come from the database, not the row passed in.
    public boolean update(OidcProviderRow row, UUID updatedBy) {
        return jdbcClient.sql("""
                        UPDATE security_oidc_provider SET display_name = :displayName, issuer_uri = :issuerUri,
                            client_id = :clientId, client_secret_encrypted = :clientSecret, scopes = :scopes,
                            enabled = :enabled, updated_by = :updatedBy, updated_at = NOW(), version = version + 1
                        WHERE registration_id = :registrationId
                        """)
                .params(params(row, updatedBy))
                .update() == 1;
    }

    public boolean delete(String registrationId) {
        return jdbcClient.sql("DELETE FROM security_oidc_provider WHERE registration_id = :registrationId")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .update() == 1;
    }

    private static Map<String, Object> params(OidcProviderRow row, UUID updatedBy) {
        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_REGISTRATION_ID, row.registrationId());
        params.put("displayName", row.displayName());
        params.put("issuerUri", row.issuerUri());
        params.put("clientId", row.clientId());
        params.put("clientSecret", row.clientSecretEncrypted());
        params.put("scopes", row.scopes());
        params.put("enabled", row.enabled());
        params.put("updatedBy", updatedBy);
        return params;
    }
}
