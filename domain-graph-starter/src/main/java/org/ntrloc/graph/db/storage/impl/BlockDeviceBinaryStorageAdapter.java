package org.ntrloc.graph.db.storage.impl;

import com.google.common.base.Splitter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.db.impl.HashingBinaryDataWriter;
import org.ntrloc.graph.db.storage.BinaryHash;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.storage.BinaryStorageAdapterConfiguration;
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

    private static final Logger LOG = LogManager.getLogger(BlockDeviceBinaryStorageAdapter.class);

    private File temporaryStorageFolder;

    private File permanentStorageFolder;

    private Map<String, File> tempFileMap = new HashMap<>();

    public BlockDeviceBinaryStorageAdapter(BinaryStorageAdapterConfiguration configuration) throws IOException {
        String location = configuration.getLocation();

        File topLevelFile = new File(location);
        if (!topLevelFile.exists()) {
            if (configuration.isAutocreate()) {
                boolean created = topLevelFile.mkdirs();
                if (!created) {
                    throw new RuntimeException("Storage location could not be created: " + location);
                }
            } else {
                throw new IllegalArgumentException("Storage location does not exist: " + location);
            }
        } else {
            temporaryStorageFolder = new File(location, "temp");
            if (temporaryStorageFolder.exists()) {
                if (!temporaryStorageFolder.isDirectory()) {
                    throw new IllegalArgumentException("Temporary storage location is not a directory: " + temporaryStorageFolder.getAbsolutePath());
                }
            } else {
                boolean created = temporaryStorageFolder.mkdirs();
                if (created) {
                    LOG.info("Created temporary binary storage location {}", temporaryStorageFolder.getAbsolutePath());
                } else {
                    throw new IllegalArgumentException("Temporary storage location could not be created: " + temporaryStorageFolder.getAbsolutePath());
                }
            }

            permanentStorageFolder = new File(location, "permanent");
            if (permanentStorageFolder.exists()) {
                if (!permanentStorageFolder.isDirectory()) {
                    throw new IllegalArgumentException("Permanent storage location is not a directory: " + permanentStorageFolder.getAbsolutePath());
                }
            } else {
                boolean created = permanentStorageFolder.mkdirs();
                if (created) {
                    LOG.info("Created permanent binary storage location {}", permanentStorageFolder.getAbsolutePath());
                } else {
                    throw new IllegalArgumentException("Permanent storage location could not be created: " + permanentStorageFolder.getAbsolutePath());
                }
            }
        }
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
    public BinaryHash close(HashingBinaryDataWriter writer) throws IOException {
        BinaryHash dataHash = writer.close();
        String id = writer.getId();

        File outputFile = tempFileMap.remove(id);
        if (outputFile != null && outputFile.exists()) {

            Path permanentPath = permanentStorageFolder.toPath();
            Path permanentFileRelPath = getPermanentStorageRelativeLocation(dataHash);
            Path permanentFilePath = permanentPath.resolve(permanentFileRelPath);

            File permanentFile = permanentFilePath.toFile();
            if (permanentFile.exists()) {
                LOG.info("Found permanent file for hash {}", dataHash);
                boolean deleted = outputFile.delete();
                if (!deleted) {
                    LOG.warn("Failed to delete temporary file {}", outputFile.getAbsolutePath());
                }
            } else {
                LOG.info("Moving temporary file to permanent storage path {}", permanentFile.getAbsolutePath());
                Path permanentFileParentPath = permanentFilePath.getParent();
                if (!permanentFileParentPath.toFile().exists()) {
                    Files.createDirectories(permanentFileParentPath);
                }
                boolean moved = outputFile.renameTo(permanentFile);
                if (!moved) {
                    LOG.warn("Failed to move temporary file {} to permanent storage path {}", outputFile.getAbsolutePath(), permanentFile.getAbsolutePath());
                }
            }
            return dataHash;
        } else {
            throw new IOException("Could not find temporary storage file " + outputFile.getAbsolutePath() + " for binary writer " + id);
        }
    }

    @Override
    public InputStream openReader(BinaryHash hash) throws IOException {
        Path permanentPath = permanentStorageFolder.toPath();
        Path permanentFileRelPath = getPermanentStorageRelativeLocation(hash);
        Path permanentFilePath = permanentPath.resolve(permanentFileRelPath);
        File permanentFile = permanentFilePath.toFile();
        return new FileInputStream(permanentFile);
    }

    private Path getPermanentStorageRelativeLocation(BinaryHash binaryHash) {
        String sha256Hash = binaryHash.getSha256Hash();
        String md5Hash = binaryHash.getMd5Hash();

        var shaSplit = Splitter.fixedLength(4).split(sha256Hash);
        String shaPath = StreamSupport.stream(shaSplit.spliterator(), false).collect(Collectors.joining("/"));
        String permanentPath = String.format("%s/%s-%s", shaPath, sha256Hash, md5Hash);

        return Path.of(permanentPath);
    }

    @Override
    public void abandon(HashingBinaryDataWriter writer) {
        throw new RuntimeException("Not implemented yet");
    }
}
