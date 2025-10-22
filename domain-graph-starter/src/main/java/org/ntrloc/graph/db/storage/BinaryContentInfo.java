package org.ntrloc.graph.db.storage;

public class BinaryContentInfo {

    private String sha256Hash;

    private String md5Hash;

    private String mimeType;

    public BinaryContentInfo(String sha256Hash, String md5Hash) {
        this.sha256Hash = sha256Hash;
        this.md5Hash = md5Hash;
    }

    public String getSha256Hash() {
        return sha256Hash;
    }

    public void setSha256Hash(String sha256Hash) {
        this.sha256Hash = sha256Hash;
    }

    public String getMd5Hash() {
        return md5Hash;
    }

    public void setMd5Hash(String md5Hash) {
        this.md5Hash = md5Hash;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
}
