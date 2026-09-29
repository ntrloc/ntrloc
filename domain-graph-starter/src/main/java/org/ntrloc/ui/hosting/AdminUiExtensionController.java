package org.ntrloc.ui.hosting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// The core admin UI fetches this once at boot, before rendering its own routes, and merges each
// entry's nav item + route into its own -- see AdminUiExtensionScanner's own comment for how these
// are discovered.
@RestController
@RequestMapping("/api/admin/ui-extensions")
public class AdminUiExtensionController {

    private final AdminUiExtensionScanner scanner;

    public AdminUiExtensionController(AdminUiExtensionScanner scanner) {
        this.scanner = scanner;
    }

    @GetMapping
    List<AdminUiExtensionDescriptor> listExtensions() {
        return scanner.extensions();
    }
}
