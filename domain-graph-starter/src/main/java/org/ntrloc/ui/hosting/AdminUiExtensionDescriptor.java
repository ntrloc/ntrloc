package org.ntrloc.ui.hosting;

// What the core admin UI needs to merge one discovered extension into its own nav/routes.
// bundleUrl is always "/admin-ui-extensions/<key>/bundle.js" -- derived from where the extension
// was found, never read from the manifest itself, so there's no way for a manifest to claim a
// bundle path other than its own.
public record AdminUiExtensionDescriptor(String navLabel, String navPath, String bundleUrl) {
}
