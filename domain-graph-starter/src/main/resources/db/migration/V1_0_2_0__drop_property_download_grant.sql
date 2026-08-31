-- Drop the separate "download" property grant. For a binary property, read is now equivalent to
-- download -- if a principal can read the property they can fetch its bytes; there is no second
-- gate. The column was stored (via the Access UI) but never enforced anywhere in the projection
-- path, so removing it is a cleanup, not a behaviour change on its own.
ALTER TABLE marker_grant_property      DROP COLUMN can_download;
ALTER TABLE marker_grant_link_property DROP COLUMN can_download;
