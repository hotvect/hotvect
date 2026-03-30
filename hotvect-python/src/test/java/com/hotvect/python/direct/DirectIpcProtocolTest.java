package com.hotvect.python.direct;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DirectIpcProtocolTest {
    @Test
    void encodeWork_omitsTraceparentWhenNull() throws Exception {
        byte[] p0 = new byte[]{1, 2, 3};
        byte[] p1 = new byte[]{4, 5};
        byte[] payload = DirectIpcProtocol.encodeWork("req#1", 7, List.of(p0, p1), null);

        ByteBuffer buf = ByteBuffer.wrap(payload);
        assertEquals(DirectIpcProtocol.OP_WORK, buf.get());
        assertEquals("req#1", readUtf8U16(buf));
        assertEquals(7, buf.getInt());
        assertEquals(2, buf.getInt());
        assertArrayEquals(p0, readBytes(buf));
        assertArrayEquals(p1, readBytes(buf));
        assertEquals(0, buf.remaining());
    }

    @Test
    void encodeWork_appendsTraceparentWhenNonBlank() throws Exception {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        byte[] payload = DirectIpcProtocol.encodeWork("req#2", 1, List.of(), traceparent);

        ByteBuffer buf = ByteBuffer.wrap(payload);
        assertEquals(DirectIpcProtocol.OP_WORK, buf.get());
        assertEquals("req#2", readUtf8U16(buf));
        assertEquals(1, buf.getInt());
        assertEquals(0, buf.getInt());
        assertEquals(traceparent, readUtf8U16(buf));
        assertEquals(0, buf.remaining());
    }

    @Test
    void decodeResult_supportsOptionalDebugJson() throws Exception {
        byte[] withoutDebug = encodeResultPayload("r1", new float[]{1.25f, -2.5f}, null);
        DirectIpcProtocol.DecodedMessage decoded = DirectIpcProtocol.decode(withoutDebug);
        DirectIpcProtocol.DecodedResult r = (DirectIpcProtocol.DecodedResult) decoded;
        assertEquals("r1", r.requestId());
        assertArrayEquals(new float[]{1.25f, -2.5f}, r.output());
        assertNull(r.debugJson());

        String debug = "{\"k\":1}";
        byte[] withDebug = encodeResultPayload("r2", new float[]{42f}, debug);
        DirectIpcProtocol.DecodedResult r2 = (DirectIpcProtocol.DecodedResult) DirectIpcProtocol.decode(withDebug);
        assertEquals("r2", r2.requestId());
        assertArrayEquals(new float[]{42f}, r2.output());
        assertEquals(debug, r2.debugJson());
    }

    private static byte[] encodeResultPayload(String requestId, float[] output, String debugJsonOrNull) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeByte(DirectIpcProtocol.OP_RESULT);
        writeUtf8U16(out, requestId);
        out.writeInt(output.length);
        for (float v : output) {
            out.writeFloat(v);
        }
        if (debugJsonOrNull != null) {
            byte[] bytes = debugJsonOrNull.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
        out.flush();
        return baos.toByteArray();
    }

    private static void writeUtf8U16(DataOutputStream out, String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readUtf8U16(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuffer buf) {
        int len = buf.getInt();
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return bytes;
    }
}
