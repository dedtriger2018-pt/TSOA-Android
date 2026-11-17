package com.winlator.tsoa;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Bounded PCM WAV reader; the loop length is counted in interleaved audio frames. */
final class TsoaPcmLoop {
    static final int MAX_PCM_BYTES = 5 * 1024 * 1024;
    final byte[] pcm;
    final int sampleRate;
    final int channels;
    final int frameCount;

    private TsoaPcmLoop(byte[] pcm, int sampleRate, int channels) {
        this.pcm = pcm;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frameCount = pcm.length / (channels * 2);
    }

    static TsoaPcmLoop read(InputStream input) throws IOException {
        byte[] header = new byte[16];
        readFully(input, header, 12);
        if (!matches(header, 0, "RIFF") || !matches(header, 8, "WAVE"))
            throw new IOException("O som do lobby não é um WAV PCM.");
        int rate = 0, channels = 0, align = 0;
        boolean formatSeen = false;
        for (int chunks = 0; chunks < 32; chunks++) {
            readFully(input, header, 8);
            long size = Integer.toUnsignedLong(integer(header, 4));
            if (size > 16 * 1024 * 1024) throw new IOException("WAV chunk too large.");
            if (matches(header, 0, "fmt ")) {
                if (size < 16 || size > 4096) throw new IOException("Invalid WAV format length.");
                readFully(input, header, 16);
                channels = unsignedShort(header, 2);
                rate = integer(header, 4);
                align = unsignedShort(header, 12);
                if (unsignedShort(header, 0) != 1 || unsignedShort(header, 14) != 16 ||
                    (channels != 1 && channels != 2) || rate < 16000 || rate > 48000 ||
                    align != channels * 2 || integer(header, 8) != rate * align)
                    throw new IOException("Unsupported PCM WAV format.");
                skipFully(input, size - 16 + (size & 1));
                formatSeen = true;
            }
            else if (matches(header, 0, "data")) {
                if (!formatSeen || size < align || size > MAX_PCM_BYTES || size % align != 0)
                    throw new IOException("Invalid or oversized PCM loop.");
                byte[] pcm = new byte[(int)size];
                readFully(input, pcm, pcm.length);
                return new TsoaPcmLoop(pcm, rate, channels);
            }
            else skipFully(input, size + (size & 1));
        }
        throw new IOException("PCM data not found.");
    }

    private static boolean matches(byte[] bytes, int offset, String text) {
        for (int i = 0; i < text.length(); i++) if (bytes[offset + i] != text.charAt(i)) return false;
        return true;
    }

    private static int unsignedShort(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
    private static int integer(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }
    private static void readFully(InputStream input, byte[] target, int count) throws IOException {
        int offset = 0;
        while (offset < count) {
            int read = input.read(target, offset, count - offset);
            if (read < 0) throw new EOFException("Truncated PCM WAV.");
            if (read == 0) {
                int value = input.read();
                if (value < 0) throw new EOFException("Truncated PCM WAV.");
                target[offset++] = (byte)value;
            }
            else offset += read;
        }
    }
    private static void skipFully(InputStream input, long count) throws IOException {
        while (count > 0) {
            long skipped = input.skip(count);
            if (skipped > 0) count -= skipped;
            else {
                if (input.read() < 0) throw new EOFException("Truncated PCM WAV.");
                count--;
            }
        }
    }
}
