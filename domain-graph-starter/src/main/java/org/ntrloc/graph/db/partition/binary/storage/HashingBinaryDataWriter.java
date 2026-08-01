package org.ntrloc.graph.db.partition.binary.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class HashingBinaryDataWriter {

    private final String id;
    private final OutputStream hashingOutputStream;
    private final MessageDigest sha256Digest;
    private final MessageDigest md5Digest;

    @SuppressWarnings("java:S4790")
    public HashingBinaryDataWriter(String id, OutputStream outputStream) throws NoSuchAlgorithmException {
        this.id = id;
        sha256Digest = MessageDigest.getInstance("SHA-256");
        DigestOutputStream sha256Stream = new DigestOutputStream(outputStream, sha256Digest);
        md5Digest = MessageDigest.getInstance("MD5");
        this.hashingOutputStream = new DigestOutputStream(sha256Stream, md5Digest);
    }

    public void write(byte[] data) throws IOException {
        hashingOutputStream.write(data);
    }

    public String getId() {
        return id;
    }

    public BinaryContentInfo close() throws IOException {
        hashingOutputStream.flush();
        hashingOutputStream.close();
        return new BinaryContentInfo(hex(sha256Digest), hex(md5Digest));
    }

    private String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
