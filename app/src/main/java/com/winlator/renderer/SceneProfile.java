package com.winlator.renderer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A locally prepared background and its registered structural maps. Rows start at the top. */
public final class SceneProfile {
    public static final int MAX_DIMENSION = 256;
    public static final int MAX_FILE_BYTES = 56 + 8 * MAX_DIMENSION * MAX_DIMENSION;
    public final int width, height, sourceWidth, sourceHeight;
    public final byte[] reference, structure;

    private SceneProfile(int width, int height, int sourceWidth, int sourceHeight,
                         byte[] reference, byte[] structure) {
        this.width = width;
        this.height = height;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.reference = reference;
        this.structure = structure;
    }

    public static SceneProfile read(InputStream source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        DigestInputStream checked = new DigestInputStream(source, digest);
        DataInputStream data = new DataInputStream(checked);
        // Big-endian ASCII "TSOSCENE", version 1.
        if (data.readLong() != 0x54534f5343454e45L || data.readInt() != 1)
            throw new IOException("Unsupported scene profile");
        int sourceWidth = data.readInt(), sourceHeight = data.readInt();
        int width = data.readUnsignedShort(), height = data.readUnsignedShort();
        if (sourceWidth < 1 || sourceHeight < 1 || sourceWidth > 8192 || sourceHeight > 8192
                || (long)sourceWidth * sourceHeight > 32_000_000
                || width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || width > sourceWidth || height > sourceHeight)
            throw new IOException("Invalid scene dimensions");
        int sourceEdge = Math.max(sourceWidth, sourceHeight), edge = Math.max(width, height);
        if (width != Math.max(1, (sourceWidth * edge + sourceEdge / 2) / sourceEdge)
                || height != Math.max(1, (sourceHeight * edge + sourceEdge / 2) / sourceEdge))
            throw new IOException("Invalid scene aspect ratio");

        byte[] reference = new byte[4 * width * height], structure = new byte[reference.length];
        data.readFully(reference);
        data.readFully(structure);
        checked.on(false);
        byte[] expected = new byte[32];
        data.readFully(expected);
        if (!MessageDigest.isEqual(expected, digest.digest()) || data.read() != -1)
            throw new IOException("Invalid scene integrity");
        for (int i = 3; i < reference.length; i += 4) {
            if ((reference[i] & 255) != 255) throw new IOException("Nonopaque scene reference");
        }
        return new SceneProfile(width, height, sourceWidth, sourceHeight, reference, structure);
    }
}
