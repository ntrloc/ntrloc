package org.ntrloc.graph.db.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.db.EntityManager;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;


public class MultipartParser {

    private enum State {
        SEEKING_INITIAL_BOUNDARY,
        GATHERING_PART_HEADERS,
        COLLECTING_BODY,
        SEEKING_SUBSEQUENT_BOUNDARY,
        END_MULTIPART
    }

    private EntityManager entityManager;

    private State currentState = State.SEEKING_INITIAL_BOUNDARY;

    private byte[] initialBoundaryBytes;

    private byte[] subsequentBoundaryBytesStart;

    private byte[] subsequentBoundaryBytesContinuing;

    private byte[] subsequentBoundaryBytesEnding;

    private static final byte[] MULTIPART_LINE_SEPARATOR_BYTES = new byte[] { 13, 10, 13, 10 };

    private static final byte[] MULTIPART_END_BYTES = new byte[] { 45, 45, 13, 10 };

    private ArrayBuffer buffer = new ArrayBuffer(64000);

    @SuppressWarnings("java:S5852")
    private Pattern contentIdPattern = Pattern.compile("form-data; name=\"(.+)\"; filename=\".+\"");

    private Pattern headerPattern = Pattern.compile("([^:]+): (.+)");

    private static final Logger LOG = LogManager.getLogger(MultipartParser.class);

    private Map<String, String> partHashMap = new HashMap<>();

    private String currentPartId;

    private HashingBinaryDataWriter hashingBinaryDataWriter;

    public MultipartParser(String boundary, EntityManager entityManager) {
        this.entityManager = entityManager;
        LOG.info("Created multipart parser with boundary {}", boundary);
        this.initialBoundaryBytes = ("--" + boundary + "\r\n").getBytes(UTF_8);
        this.subsequentBoundaryBytesStart = ("\r\n--" + boundary).getBytes(UTF_8); // improve this to not include \r\n this way
        this.subsequentBoundaryBytesContinuing = ("\r\n--" + boundary + "\r\n").getBytes(UTF_8); // improve this to not include \r\n this way
        this.subsequentBoundaryBytesEnding = ("\r\n--" + boundary + "--").getBytes(UTF_8); // improve this to not include \r\n this way
    }

    public Mono<Map<String, String>> parse(Flux<DataBuffer> bufferFlux) {
        return bufferFlux.map(dataBuffer -> {
            try {
                DataBufferUtils.retain(dataBuffer);
                process(dataBuffer);
                return dataBuffer;
            } catch (IOException ioe) {
                LOG.error("Error processing data", ioe);
                throw Exceptions.propagate(ioe);
            } finally {
                DataBufferUtils.release(dataBuffer);
            }
        }).then(Mono.just(partHashMap));
    }

    private void process(DataBuffer dataBuffer) throws IOException {
        int byteCount = dataBuffer.readableByteCount();
        LOG.info("Data buffer is adding " + byteCount + " bytes");
        buffer.write(dataBuffer);
        processBuffer();
    }

    // called when an individual part of a multipart upload has started
    private void partStarted(Map<String, String> partHeaders) throws IOException {
        LOG.info("Part started with headers {}", partHeaders);
        Optional<String> opt = partHeaders.entrySet().stream().filter(entry -> entry.getKey().contains("Content-Disposition")).findAny().map(Map.Entry::getValue);
        if (opt.isPresent()) {
            String disposition = opt.get();
            Matcher matcher = contentIdPattern.matcher(disposition);
            if (matcher.matches()) {
                currentPartId = matcher.group(1);
                hashingBinaryDataWriter = entityManager.openWriter();
            } else {
                throw new IllegalStateException(disposition + " is not a valid Content-Disposition header");
            }
        } else {
            throw new IllegalStateException("Content disposition header not found");
        }
    }

    // called when the body of a part has been read
    private void bodyBytesRead(byte[] bytes) throws IOException {
        LOG.info("BodyBytes read: {}", bytes.length);
        hashingBinaryDataWriter.write(bytes);
    }

    // called when a part ends
    private void partEnded() throws IOException {
        LOG.info("Part ended");
        String dataId = entityManager.commitBinary(hashingBinaryDataWriter);
        partHashMap.put(currentPartId, dataId);
    }

    // called when all parsing is complete
    private void parseEnded() {
        LOG.info("Parse ended");
    }

    private void processBuffer() throws IOException {
        boolean continuationDesired = false;
        LOG.info("Processing with current state {}", currentState);

        if (currentState.equals(State.SEEKING_INITIAL_BOUNDARY)) {
            ArrayBuffer.MatchResult result = buffer.match(0, initialBoundaryBytes);

            if (result instanceof ArrayBuffer.NoMatchResult noMatch) {
                System.out.println("No match :(");
            } else if (result instanceof ArrayBuffer.PartialMatchResult partialMatch) {
                System.out.println("Partial match: " + result);
            } else if (result instanceof ArrayBuffer.CompleteMatchResult completeMatch) {
                System.out.println("Complete boundary match: " + result);
                if (completeMatch.startPosition == 0) {
                    // end position is the position of the last boundary character, so we add 1 to position past the
                    // boundary and then 1 more to position past the newline that follows
                    int skipCount = completeMatch.endPosition;

                    // remove the boundary from the buffer
                    buffer.skip(skipCount);
                    LOG.info("Dropped boundary");

                    this.currentState = State.GATHERING_PART_HEADERS;
                    continuationDesired = true;

                } else {
                    throw new RuntimeException("Malformed boundary! " + completeMatch);
                }
            }
        } else if (currentState.equals(State.GATHERING_PART_HEADERS)) {
            ArrayBuffer.MatchResult result = buffer.match(0, MULTIPART_LINE_SEPARATOR_BYTES);
            if (result instanceof ArrayBuffer.CompleteMatchResult completeMatch) {
                LOG.info("Complete header match {}", completeMatch);

                int count = completeMatch.endPosition + 1;
                byte[] headerBytes = new byte[count];
                buffer.read(headerBytes);
                String headerString = new String(headerBytes).trim();
                List<String> lines = Arrays.stream(headerString.split("\r\n")).toList();
                Map<String, String> headers = new HashMap<>();
                lines.forEach(line -> {
                    Matcher m = headerPattern.matcher(line);
                    if (m.matches()) {
                        String headerName = m.group(1);
                        String headerValue = m.group(2);
                        headers.put(headerName, headerValue);
                    }
                });
                partStarted(headers);
                this.currentState = State.COLLECTING_BODY;
                continuationDesired = true;
            } else {
                LOG.warn("Got match result {} while gathering part headers", result);
            }
        } else if (currentState.equals(State.COLLECTING_BODY)) {
            LOG.debug("Collecting body");
            ArrayBuffer.MatchResult result = buffer.match(0, subsequentBoundaryBytesStart);
            if (result instanceof ArrayBuffer.NoMatchResult noMatch) {
                LOG.info("No match :( " + buffer.available());
                byte[] bodyBytes = new byte[buffer.available()];
                buffer.read(bodyBytes);
                bodyBytesRead(bodyBytes);
            } else if (result instanceof ArrayBuffer.PartialMatchResult partialMatch) {
                LOG.info("Partial match: " + result);
                if (partialMatch.startPosition > 0) {
                    byte[] bodyBytes = new byte[partialMatch.startPosition];
                    buffer.read(bodyBytes);
                    bodyBytesRead(bodyBytes);
                }
            } else if (result instanceof ArrayBuffer.CompleteMatchResult completeMatch) {
                LOG.info("Complete body match " + completeMatch);
                byte[] bodyBytes = new byte[completeMatch.startPosition];
                buffer.read(bodyBytes);
                bodyBytesRead(bodyBytes);
                partEnded();
                this.currentState = State.SEEKING_SUBSEQUENT_BOUNDARY;
                continuationDesired = true;
            }
        } else if (currentState.equals(State.SEEKING_SUBSEQUENT_BOUNDARY)) {
            ArrayBuffer.MatchResult continuingResult = buffer.match(0, subsequentBoundaryBytesContinuing);
            if (continuingResult instanceof ArrayBuffer.CompleteMatchResult completeMatch) {
                LOG.info("Found continuing boundary match {}", completeMatch);

                // move the read pointer to one character past the boundary
                int skipCount = completeMatch.endPosition + 1;

                byte[] peekBytes = buffer.peekRead(skipCount);
                LOG.info("Dropping boundary {}", new String(peekBytes));

                // remove the boundary from the buffer
                buffer.skip(skipCount);

                this.currentState = State.GATHERING_PART_HEADERS;
                //continuationDesired = true;
            } else {
                ArrayBuffer.MatchResult endResult = buffer.match(0, subsequentBoundaryBytesEnding);
                if (endResult instanceof ArrayBuffer.CompleteMatchResult completeMatch) {
                    LOG.info("Found end boundary match {}", completeMatch);
                    this.currentState = State.END_MULTIPART;
                    parseEnded();
                } else {
                    int availableBytes = buffer.available();
                    byte[] b = buffer.peekRead(availableBytes);
                    LOG.info("No boundary match found yet; available string is {}", new String(b));
                    //continuationDesired = true;
                }
            }

                /*




                // this may be the ending boundary, so check that...
                ArrayBuffer.MatchResult crlfResult = buffer.match(0, new byte[] { 13, 10 });

                if (crlfResult instanceof ArrayBuffer.CompleteMatchResult completeResult) {
                    if (completeResult.startPosition == 0) { // if the boundary is followed by CRLF, there's more content
                        this.currentState = State.GATHERING_PART_HEADERS;
                        continuationDesired = true;
                    }
                } else {
                    ArrayBuffer.MatchResult endResult = buffer.match(0, new byte[] { 45, 45 });
                    if (endResult instanceof ArrayBuffer.CompleteMatchResult completeResult) {
                        this.currentState = State.END_MULTIPART;
                        parseEnded();
                    } else {
                        peekBytes = buffer.peekRead(2);
                        LOG.info("Next 2 bytes after boundary are {}", peekBytes);

                    }
                }

                 */

        }

        if (continuationDesired) {
            processBuffer();
        }
    }

}
