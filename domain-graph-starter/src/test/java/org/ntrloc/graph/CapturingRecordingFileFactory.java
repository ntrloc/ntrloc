package org.ntrloc.graph;

import org.testcontainers.containers.DefaultRecordingFileFactory;
import org.testcontainers.containers.VncRecordingContainer;

import java.io.File;
import java.util.Objects;

/**
 * A recording file factory that captures the name of the recorded file.
 */
public class CapturingRecordingFileFactory extends DefaultRecordingFileFactory {

    private String priorRetrievedBaseName;
    private String newRecordingBaseName;

    @Override
    public File recordingFileForTest(File vncRecordingDirectory, String prefix, boolean succeeded, VncRecordingContainer.VncRecordingFormat recordingFormat) {
        File file = super.recordingFileForTest(vncRecordingDirectory, prefix, succeeded, recordingFormat);
        newRecordingBaseName = file.getName().replace(".mp4", "");
        return file;
    }

    public String getRecordingBaseName() {
       if (newRecordingBaseName == null || Objects.equals(priorRetrievedBaseName, newRecordingBaseName)) {
           return null;
       } else {
           priorRetrievedBaseName = newRecordingBaseName;
           return newRecordingBaseName;
       }
    }

}
