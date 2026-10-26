package org.ntrloc.graph.db.partition.security.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// security_ldap_config's single row. The password column holds SecretCipher output, never plaintext.
@Component
public class LdapConfigurationRepository {

    public record LdapConfigurationRow(boolean enabled, String url, String baseDn, String bindDn,
                                       String bindPasswordEncrypted, String userSearchBase, String userSearchFilter,
                                       String emailAttribute, String displayNameAttribute,
                                       long version, OffsetDateTime updatedAt, UUID updatedBy) {}

    private static final RowMapper<LdapConfigurationRow> ROW = (rs, _) -> new LdapConfigurationRow(
            rs.getBoolean("enabled"),
            rs.getString("url"),
            rs.getString("base_dn"),
            rs.getString("bind_dn"),
            rs.getString("bind_password_encrypted"),
            rs.getString("user_search_base"),
            rs.getString("user_search_filter"),
            rs.getString("email_attribute"),
            rs.getString("display_name_attribute"),
            rs.getLong("version"),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("updated_by", UUID.class));

    private final JdbcClient jdbcClient;

    public LdapConfigurationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<LdapConfigurationRow> find() {
        return jdbcClient.sql("SELECT * FROM security_ldap_config").query(ROW).optional();
    }

    public Optional<Long> findVersion() {
        return jdbcClient.sql("SELECT version FROM security_ldap_config").query(Long.class).optional();
    }

    // version and updated_at come from the database, not the row passed in.
    public void save(LdapConfigurationRow row, UUID updatedBy) {
        jdbcClient.sql("""
                        INSERT INTO security_ldap_config (enabled, url, base_dn, bind_dn, bind_password_encrypted,
                            user_search_base, user_search_filter, email_attribute, display_name_attribute, updated_by)
                        VALUES (:enabled, :url, :baseDn, :bindDn, :bindPassword,
                            :userSearchBase, :userSearchFilter, :emailAttribute, :displayNameAttribute, :updatedBy)
                        ON CONFLICT (id) DO UPDATE SET
                            enabled = EXCLUDED.enabled, url = EXCLUDED.url, base_dn = EXCLUDED.base_dn,
                            bind_dn = EXCLUDED.bind_dn, bind_password_encrypted = EXCLUDED.bind_password_encrypted,
                            user_search_base = EXCLUDED.user_search_base, user_search_filter = EXCLUDED.user_search_filter,
                            email_attribute = EXCLUDED.email_attribute,
                            display_name_attribute = EXCLUDED.display_name_attribute,
                            updated_by = EXCLUDED.updated_by, updated_at = NOW(),
                            version = security_ldap_config.version + 1
                        """)
                .param("enabled", row.enabled())
                .param("url", row.url())
                .param("baseDn", row.baseDn())
                .param("bindDn", row.bindDn())
                .param("bindPassword", row.bindPasswordEncrypted())
                .param("userSearchBase", row.userSearchBase())
                .param("userSearchFilter", row.userSearchFilter())
                .param("emailAttribute", row.emailAttribute())
                .param("displayNameAttribute", row.displayNameAttribute())
                .param("updatedBy", updatedBy)
                .update();
    }
}
