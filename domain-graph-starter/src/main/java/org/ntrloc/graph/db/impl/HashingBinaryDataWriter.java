package org.ntrloc.graph.db.impl;

import org.ntrloc.graph.db.storage.BinaryHash;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class HashingBinaryDataWriter {

    private String id;

    private OutputStream hashingOutputStream;

    private  MessageDigest sha256Digest;

    MessageDigest md5Digest;

    @SuppressWarnings("java:S4790")
    public HashingBinaryDataWriter(String id, OutputStream outputStream) throws NoSuchAlgorithmException {
        this.id = id;

        sha256Digest = MessageDigest.getInstance("SHA-256");
        DigestOutputStream sha256OutputStream = new DigestOutputStream(outputStream, sha256Digest);

        md5Digest = MessageDigest.getInstance("MD5");
        DigestOutputStream md5OutputStream = new DigestOutputStream(sha256OutputStream, md5Digest);

        this.hashingOutputStream = md5OutputStream;
    }

    public void write(byte[] data) throws IOException {
        hashingOutputStream.write(data);
    }

    public String getId() {
        return id;
    }

    public BinaryHash close() throws IOException {
        hashingOutputStream.flush();
        hashingOutputStream.close();

        String sha256Hash = getHash(sha256Digest);
        String md5Hash = getHash(md5Digest);
        return new BinaryHash(sha256Hash, md5Hash);
    }

    private String getHash(MessageDigest digest) {
        var digestBytes = digest.digest();
        return HexFormat.of().formatHex(digestBytes);
    }

}
