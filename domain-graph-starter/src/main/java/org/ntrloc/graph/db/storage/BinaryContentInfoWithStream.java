package org.ntrloc.graph.db.storage;

import java.io.InputStream;

public class BinaryContentInfoWithStream extends BinaryContentInfo {

    private InputStream binaryStream;

    public BinaryContentInfoWithStream(String sha256Hash, String md5Hash) {
        super(sha256Hash, md5Hash);
    }

    public InputStream getBinaryStream() {
        return binaryStream;
    }

    public void setBinaryStream(InputStream binaryStream) {
        this.binaryStream = binaryStream;
    }

}
