package org.nterloc.graph.db.storage;

public class BinaryHash {

    private String sha256Hash;

    private String md5Hash;

    public BinaryHash(String sha256Hash, String md5Hash) {
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
}
