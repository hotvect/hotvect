package com.hotvect.python.direct;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class DirectIpcProtocol {
    static final byte OP_STARTUP = 1;
    static final byte OP_STARTUP_ACK = 2;
    static final byte OP_WORK = 3;
    static final byte OP_RESULT = 4;
    static final byte OP_GET_WORK = 5;
    static final byte OP_SHUTDOWN = 6;
    static final byte OP_REQUEST_ERROR = 7;
    static final byte OP_WORKER_ERROR = 8;

    static final byte STARTUP_READY = 0;
    static final byte STARTUP_CANNOT_START = 1;

    private DirectIpcProtocol() {
    }

    static byte[] encodeStartupAck() {
        return new byte[]{OP_STARTUP_ACK};
    }

    static byte[] encodeShutdown() {
        return new byte[]{OP_SHUTDOWN};
    }

    static byte[] encodeWork(
            String requestId,
            int batchSize,
            List<byte[]> payloads,
            String traceparent
    ) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        DataOutputStream out = new DataOutputStream(baos);
        out.writeByte(OP_WORK);
        writeUtf8U16(out, requestId);

        out.writeInt(batchSize);
        out.writeInt(payloads.size());
        for (byte[] bytes : payloads) {
            out.writeInt(bytes.length);
            out.write(bytes);
        }

        if (traceparent != null && !traceparent.isBlank()) {
            writeUtf8U16(out, traceparent);
        }

        out.flush();
        return baos.toByteArray();
    }

    static DecodedMessage decode(byte[] payload) throws IOException {
        if (payload.length < 1) {
            throw new IOException("IPC frame too small: " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        byte op = buf.get();

        return switch (op) {
            case OP_STARTUP -> {
                long pid = readLong(buf);
                byte status = readByte(buf);
                String message = readUtf8U16(buf);
                yield new DecodedStartup(pid, status, message);
            }
            case OP_RESULT -> {
                String requestId = readUtf8U16(buf);
                int floatCount = readInt(buf);
                if (floatCount < 0) {
                    throw new IOException("Invalid float_count: " + floatCount);
                }
                float[] output = new float[floatCount];
                for (int i = 0; i < floatCount; i++) {
                    output[i] = readFloat(buf);
                }

                String debugJson = null;
                if (buf.remaining() > 0) {
                    int debugLen = readInt(buf);
                    if (debugLen < 0) {
                        throw new IOException("Invalid debug_len: " + debugLen);
                    }
                    if (debugLen > buf.remaining()) {
                        throw new EOFException("Truncated debug payload");
                    }
                    if (debugLen > 0) {
                        byte[] bytes = new byte[debugLen];
                        buf.get(bytes);
                        debugJson = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
                yield new DecodedResult(requestId, output, debugJson);
            }
            case OP_GET_WORK -> {
                if (buf.remaining() != 0) {
                    throw new IOException("GET_WORK must have empty payload");
                }
                yield new DecodedGetWork();
            }
            case OP_REQUEST_ERROR -> {
                String requestId = readUtf8U16(buf);
                String message = readUtf8U16(buf);
                yield new DecodedRequestError(requestId, message);
            }
            case OP_WORKER_ERROR -> {
                String message = readUtf8U16(buf);
                yield new DecodedWorkerError(message);
            }
            default -> throw new IOException("Unknown IPC op: " + op);
        };
    }

    static void writeFrame(OutputStream out, byte[] payload, int maxFrameBytes) throws IOException {
        if (payload.length > maxFrameBytes) {
            throw new IOException("IPC payload exceeds maxFrameBytes: " + payload.length + " > " + maxFrameBytes);
        }
        ByteBuffer hdr = ByteBuffer.allocate(4);
        hdr.putInt(payload.length);
        out.write(hdr.array());
        out.write(payload);
        out.flush();
    }

    static byte[] readFrame(InputStream in, int maxFrameBytes) throws IOException {
        byte[] hdr = readFully(in, 4);
        int len = ByteBuffer.wrap(hdr).getInt();
        if (len < 0 || len > maxFrameBytes) {
            throw new IOException("Invalid IPC frame length: " + len + " (maxFrameBytes=" + maxFrameBytes + ")");
        }
        return readFully(in, len);
    }

    private static void writeUtf8U16(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            s = "";
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("String too long: " + bytes.length);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readUtf8U16(ByteBuffer buf) throws IOException {
        int len = readU16(buf);
        if (len == 0) {
            return "";
        }
        if (buf.remaining() < len) {
            throw new EOFException("Truncated string payload");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readU16(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 2) {
            throw new EOFException("Truncated u16");
        }
        return buf.getShort() & 0xFFFF;
    }

    private static long readLong(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 8) {
            throw new EOFException("Truncated int64");
        }
        return buf.getLong();
    }

    private static int readInt(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 4) {
            throw new EOFException("Truncated int32");
        }
        return buf.getInt();
    }

    private static float readFloat(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 4) {
            throw new EOFException("Truncated float32");
        }
        return buf.getFloat();
    }

    private static byte readByte(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 1) {
            throw new EOFException("Truncated byte");
        }
        return buf.get();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(out, off, len - off);
            if (r < 0) {
                throw new EOFException("Unexpected EOF while reading " + len + " bytes");
            }
            off += r;
        }
        return out;
    }

    sealed interface DecodedMessage permits DecodedStartup, DecodedGetWork, DecodedResult, DecodedRequestError, DecodedWorkerError {
    }

    record DecodedStartup(long pid, byte status, String message) implements DecodedMessage {
    }

    record DecodedGetWork() implements DecodedMessage {
    }

    record DecodedResult(String requestId, float[] output, String debugJson) implements DecodedMessage {
    }

    record DecodedRequestError(String requestId, String message) implements DecodedMessage {
    }

    record DecodedWorkerError(String message) implements DecodedMessage {
    }
}
