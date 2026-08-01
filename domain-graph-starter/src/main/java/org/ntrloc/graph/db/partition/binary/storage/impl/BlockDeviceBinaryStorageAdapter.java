package org.ntrloc.graph.db.partition.binary.storage.impl;

import com.google.common.base.Splitter;
import org.apache.tika.Tika;
import org.ntrloc.graph.db.partition.binary.storage.BinaryContentInfo;
import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapterConfiguration;
import org.ntrloc.graph.db.partition.binary.storage.HashingBinaryDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
@ConditionalOnProperty(value = "binary.storage.strategy", havingValue = "block")
public class BlockDeviceBinaryStorageAdapter implements BinaryStorageAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BlockDeviceBinaryStorageAdapter.class);

    private final File temporaryStorageFolder;
    private final File permanentStorageFolder;
    private final Map<String, File> tempFileMap = new HashMap<>();

    public BlockDeviceBinaryStorageAdapter(BinaryStorageAdapterConfiguration configuration) throws IOException {
        File root = new File(configuration.getLocation());
        if (!root.exists()) {
            if (configuration.isAutocreate()) {
                if (!root.mkdirs()) throw new IOException("Storage location could not be created: " + root);
            } else {
                throw new IllegalArgumentException("Storage location does not exist: " + root);
            }
        }
        temporaryStorageFolder = ensureDirectory(new File(root, "temp"));
        permanentStorageFolder = ensureDirectory(new File(root, "permanent"));
    }

    @Override
    public HashingBinaryDataWriter openWriter() throws IOException {
        File outputFile;
        String uuid;
        do {
            uuid = UUID.randomUUID().toString();
            outputFile = new File(temporaryStorageFolder, uuid);
        } while (outputFile.exists());

        tempFileMap.put(uuid, outputFile);
        try {
            return new HashingBinaryDataWriter(uuid, new FileOutputStream(outputFile));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Override
    public BinaryContentInfo close(HashingBinaryDataWriter writer) throws IOException {
        BinaryContentInfo info = writer.close();
        String id = writer.getId();
        File tempFile = tempFileMap.remove(id);

        if (tempFile == null || !tempFile.exists()) {
            throw new IOException("Temporary file not found for writer " + id);
        }

        info.setLength(tempFile.length());
        try {
            info.setMimeType(new Tika().detect(tempFile));
        } catch (Exception e) {
            LOG.warn("MIME detection failed for writer {}", id, e);
        }

        Path permanentPath = permanentStorageFolder.toPath()
                .resolve(permanentRelativePath(info.getSha256Hash(), info.getMd5Hash()));
        File permanentFile = permanentPath.toFile();

        if (permanentFile.exists()) {
            LOG.debug("Deduplicating binary {}: permanent file already exists", info.getSha256Hash());
            Files.delete(tempFile.toPath());
        } else {
            Files.createDirectories(permanentPath.getParent());
            if (!tempFile.renameTo(permanentFile)) {
                LOG.warn("Failed to move temp file {} to {}", tempFile, permanentFile);
            }
        }

        return info;
    }

    @Override
    public InputStream openReader(String sha256Hash, String md5Hash) throws IOException {
        Path path = permanentStorageFolder.toPath().resolve(permanentRelativePath(sha256Hash, md5Hash));
        return new FileInputStream(path.toFile());
    }

    @Override
    public void abandon(HashingBinaryDataWriter writer) {
        File tempFile = tempFileMap.remove(writer.getId());
        if (tempFile != null && tempFile.exists()) {
            try {
                Files.delete(tempFile.toPath());
            } catch (IOException e) {
                LOG.warn("Failed to delete abandoned temp file {}", tempFile, e);
            }
        }
    }

    private Path permanentRelativePath(String sha256, String md5) {
        String shaPath = StreamSupport.stream(
                Splitter.fixedLength(4).split(sha256).spliterator(), false)
                .collect(Collectors.joining("/"));
        return Path.of(shaPath + "/" + sha256 + "-" + md5);
    }

    private File ensureDirectory(File dir) throws IOException {
        if (dir.exists()) {
            if (!dir.isDirectory()) throw new IllegalArgumentException("Not a directory: " + dir);
        } else {
            if (!dir.mkdirs()) throw new IOException("Could not create directory: " + dir);
            LOG.info("Created binary storage directory {}", dir);
        }
        return dir;
    }
}
