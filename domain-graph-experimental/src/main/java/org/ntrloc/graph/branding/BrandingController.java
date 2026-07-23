package org.ntrloc.graph.branding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The admin UI's nav bar is a static asset with no server-side templating, so it fetches the
// configured display name at runtime rather than having it injected server-side (see
// LoginController, which is server-rendered and can substitute it directly).
@RestController
@RequestMapping("/api/admin/branding")
public class BrandingController {

    public record BrandingView(String displayName) {}

    private final BrandingProperties properties;

    public BrandingController(BrandingProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    BrandingView branding() {
        return new BrandingView(properties.displayName());
    }
}
