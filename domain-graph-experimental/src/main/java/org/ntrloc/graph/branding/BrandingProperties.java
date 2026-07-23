package org.ntrloc.graph.branding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// The one configurable name shown to users -- the login page's title/heading and the admin UI's
// nav bar brand, both of which otherwise hardcode "ntrloc" literally. Each runtime sets its own
// via ntrloc.branding.display-name in application.yml.
@ConfigurationProperties(prefix = "ntrloc.branding")
public record BrandingProperties(@DefaultValue("ntrloc") String displayName) {
}
