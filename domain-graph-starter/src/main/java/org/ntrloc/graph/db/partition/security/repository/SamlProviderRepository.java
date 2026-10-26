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

// security_saml_provider and its key pairs (security_saml_provider_key). A key's private key column
// holds SecretCipher output, never plaintext.
@Component
public class SamlProviderRepository {

    private static final String PARAM_REGISTRATION_ID = "registrationId";
    private static final String PARAM_ROLE = "role";
    private static final String PROVIDER_ID = "(SELECT id FROM security_saml_provider WHERE registration_id = :registrationId)";

    public enum KeyRole { ACTIVE, NEXT, RETIRING }

    public record SamlProviderRow(String registrationId, String displayName, String metadataUri, String emailAttribute,
                                  String displayNameAttribute, boolean enabled, long version, OffsetDateTime updatedAt) {}

    public record SamlKeyRow(KeyRole role, String privateKeyEncrypted, String certificate, OffsetDateTime createdAt) {}

    private static final RowMapper<SamlProviderRow> ROW = (rs, _) -> new SamlProviderRow(
            rs.getString("registration_id"),
            rs.getString("display_name"),
            rs.getString("metadata_uri"),
            rs.getString("email_attribute"),
            rs.getString("display_name_attribute"),
            rs.getBoolean("enabled"),
            rs.getLong("version"),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<SamlKeyRow> KEY_ROW = (rs, _) -> new SamlKeyRow(
            KeyRole.valueOf(rs.getString(PARAM_ROLE)),
            rs.getString("private_key_encrypted"),
            rs.getString("certificate"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbcClient;

    public SamlProviderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SamlProviderRow> findAll() {
        return jdbcClient.sql("SELECT * FROM security_saml_provider ORDER BY display_name").query(ROW).list();
    }

    public Optional<SamlProviderRow> find(String registrationId) {
        return jdbcClient.sql("SELECT * FROM security_saml_provider WHERE registration_id = :registrationId")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .query(ROW)
                .optional();
    }

    public void insert(SamlProviderRow row, UUID updatedBy) {
        jdbcClient.sql("""
                        INSERT INTO security_saml_provider (registration_id, display_name, metadata_uri, email_attribute,
                            display_name_attribute, enabled, updated_by)
                        VALUES (:registrationId, :displayName, :metadataUri, :emailAttribute, :displayNameAttribute,
                            :enabled, :updatedBy)
                        """)
                .params(params(row, updatedBy))
                .update();
    }

    // version and updated_at come from the database.
    public boolean update(SamlProviderRow row, UUID updatedBy) {
        return jdbcClient.sql("""
                        UPDATE security_saml_provider SET display_name = :displayName, metadata_uri = :metadataUri,
                            email_attribute = :emailAttribute, display_name_attribute = :displayNameAttribute,
                            enabled = :enabled, updated_by = :updatedBy, updated_at = NOW(), version = version + 1
                        WHERE registration_id = :registrationId
                        """)
                .params(params(row, updatedBy))
                .update() == 1;
    }

    public boolean delete(String registrationId) {
        return jdbcClient.sql("DELETE FROM security_saml_provider WHERE registration_id = :registrationId")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .update() == 1;
    }

    // --- Key pairs ---

    public List<SamlKeyRow> findKeys(String registrationId) {
        return jdbcClient.sql("SELECT * FROM security_saml_provider_key WHERE provider_id = " + PROVIDER_ID
                        + " ORDER BY created_at")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .query(KEY_ROW)
                .list();
    }

    public void insertKey(String registrationId, KeyRole role, String privateKeyEncrypted, String certificate) {
        jdbcClient.sql("INSERT INTO security_saml_provider_key (provider_id, role, private_key_encrypted, certificate) "
                        + "VALUES (" + PROVIDER_ID + ", :role, :privateKey, :certificate)")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .param(PARAM_ROLE, role.name())
                .param("privateKey", privateKeyEncrypted)
                .param("certificate", certificate)
                .update();
    }

    public boolean deleteKey(String registrationId, KeyRole role) {
        return jdbcClient.sql("DELETE FROM security_saml_provider_key WHERE provider_id = " + PROVIDER_ID + " AND role = :role")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .param(PARAM_ROLE, role.name())
                .update() == 1;
    }

    public void moveKey(String registrationId, KeyRole from, KeyRole to) {
        jdbcClient.sql("UPDATE security_saml_provider_key SET role = :to WHERE provider_id = " + PROVIDER_ID + " AND role = :from")
                .param(PARAM_REGISTRATION_ID, registrationId)
                .param("from", from.name())
                .param("to", to.name())
                .update();
    }

    // A key change is a change to the provider, so each node rebuilds its registration.
    public void touch(String registrationId, UUID updatedBy) {
        jdbcClient.sql("""
                        UPDATE security_saml_provider SET updated_by = :updatedBy, updated_at = NOW(), version = version + 1
                        WHERE registration_id = :registrationId
                        """)
                .param(PARAM_REGISTRATION_ID, registrationId)
                .param("updatedBy", updatedBy)
                .update();
    }

    private static Map<String, Object> params(SamlProviderRow row, UUID updatedBy) {
        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_REGISTRATION_ID, row.registrationId());
        params.put("displayName", row.displayName());
        params.put("metadataUri", row.metadataUri());
        params.put("emailAttribute", row.emailAttribute());
        params.put("displayNameAttribute", row.displayNameAttribute());
        params.put("enabled", row.enabled());
        params.put("updatedBy", updatedBy);
        return params;
    }
}
