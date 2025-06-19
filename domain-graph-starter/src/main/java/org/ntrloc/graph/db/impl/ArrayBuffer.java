package org.ntrloc.graph.db.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.buffer.DataBuffer;

import java.util.StringJoiner;

public class ArrayBuffer {

    private byte[] array;

    private int readPosition = 0;

    private int writePosition = 0;

    private static final Logger LOG = LogManager.getLogger(ArrayBuffer.class);

    public ArrayBuffer(int bufferSize) {
        this.array = new byte[bufferSize];
    }

    public void write(DataBuffer dataBuffer) {
        int readableLength = dataBuffer.readableByteCount();
        if (writePosition + readableLength > array.length) {
            compact();
            int requiredCapacity = writePosition + readableLength;
            if (requiredCapacity > array.length) {
                LOG.info("Need to expand array; required capacity is {}", requiredCapacity);
                byte[] newArray = new byte[requiredCapacity];
                // copy the current array data into the new array
                LOG.info("Copying prior array from index 0 to index {}", writePosition);
                System.arraycopy(array, 0, newArray, 0, array.length);
                LOG.info("Writing {} bytes into new array at position {}", readableLength, writePosition);
                // copy the data buffer into the new array
                dataBuffer.read(newArray, writePosition, readableLength);
                array = newArray;
                writePosition += readableLength;
            } else {
                dataBuffer.read(array, writePosition, readableLength);
            }
        } else {
            dataBuffer.read(array, writePosition, readableLength);
            writePosition += readableLength;
        }
    }

    public int available() {
        return writePosition - readPosition;
    }

    public void skip(int count) {
        int target = readPosition + count;
        if (target > writePosition) {
            throw new IllegalArgumentException("Cannot skip read position beyond write position " + writePosition);
        } else if (target == writePosition) {
            readPosition = 0;
            writePosition = 0;
        } else {
            readPosition = target;
        }
    }

    public byte peek(int index) {
        int effectiveIndex = readPosition + index;
        if (effectiveIndex < writePosition) {
            return array[effectiveIndex];
        } else {
            throw new IllegalArgumentException("Cannot peek at or beyond write position " + writePosition + " using index " + effectiveIndex);
        }
    }

    /**
     * Reads the given number of bytes from the buffer WITHOUT advancing the read position
     */
    public byte[] peekRead(int count) {
        int endPosition = readPosition + count;
        byte[] result = new byte[endPosition - readPosition];
        System.arraycopy(array, readPosition, result, 0, result.length);
        return result;
    }

    public void read(byte[] target) {
        // read the next available bytes into the target
        int byteReadCount = Math.min(writePosition - readPosition, target.length);
        System.arraycopy(array, readPosition, target, 0, byteReadCount);
        readPosition += byteReadCount;
    }

    private void compact() {
        if (readPosition > 0) {
            if (readPosition == writePosition) {
                readPosition = 0;
                writePosition = 0;
            } else {
                int byteCount = writePosition - readPosition;
                System.arraycopy(array, readPosition, array, 0, byteCount);
                readPosition -= byteCount;
                writePosition -= byteCount;
            }
        }
    }

    public MatchResult match(int bufferStartPosition, byte[] testSequence) {
        byte start = testSequence[0];
        LOG.debug("Executing match with read position {}, write position {} and array length {}", readPosition, writePosition, array.length);
        for (int i = readPosition + bufferStartPosition; i < writePosition; i++) {
            if (array[i] == start) {
                int matchCount = 1; // we already found the first byte
                boolean mismatch = false;
                int followPosition = 1;
                for (; followPosition < testSequence.length && !mismatch; followPosition++) {
                    int bufferPosition = i + followPosition;
                    if (bufferPosition < writePosition) {
                        if (array[bufferPosition] == testSequence[followPosition]) {
                            matchCount += 1;
                        } else {
                            mismatch = true;
                        }
                    } else {
                        return new PartialMatchResult(i - readPosition);
                    }
                }
                if (matchCount == testSequence.length) {
                    return new CompleteMatchResult(i - readPosition, i - readPosition + matchCount - 1);
                }
            }
        }
        return new NoMatchResult();
    }

    class MatchResult { }
    class NoMatchResult extends MatchResult { }
    class CompleteMatchResult extends MatchResult {
        int startPosition;
        int endPosition;

        CompleteMatchResult(int startPos, int endPos) {
            this.startPosition = startPos;
            this.endPosition = endPos;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", CompleteMatchResult.class.getSimpleName() + "[", "]")
                    .add("startPosition=" + startPosition)
                    .add("endPosition=" + endPosition)
                    .toString();
        }
    }
    class PartialMatchResult extends MatchResult {
        int startPosition;

        public PartialMatchResult(int startPosition) {
            this.startPosition = startPosition;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", PartialMatchResult.class.getSimpleName() + "[", "]")
                    .add("startPosition=" + startPosition)
                    .toString();
        }
    }

}
