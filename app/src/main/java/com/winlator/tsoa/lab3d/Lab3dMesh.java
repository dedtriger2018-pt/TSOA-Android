package com.winlator.tsoa.lab3d;

import android.opengl.GLES30;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/** Static instanced geometry. Every object remains real depth-tested scene geometry. */
final class Lab3dMesh {
    private int vao, vertexBuffer, instanceBuffer;
    private final int vertices, instances;

    Lab3dMesh(float[] geometry, float[] objects) {
        vertices = geometry.length / 6;
        instances = objects.length / 24;
        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0];
        GLES30.glBindVertexArray(vao);
        GLES30.glGenBuffers(1, ids, 0); vertexBuffer = ids[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, geometry.length * 4, buffer(geometry), GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12);
        GLES30.glGenBuffers(1, ids, 0); instanceBuffer = ids[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, objects.length * 4, buffer(objects), GLES30.GL_STATIC_DRAW);
        for (int i = 0; i < 6; i++) {
            int slot = i + 2;
            GLES30.glEnableVertexAttribArray(slot);
            GLES30.glVertexAttribPointer(slot, 4, GLES30.GL_FLOAT, false, 96, i * 16);
            GLES30.glVertexAttribDivisor(slot, 1);
        }
        GLES30.glBindVertexArray(0);
    }

    void draw() {
        GLES30.glBindVertexArray(vao);
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, vertices, instances);
        GLES30.glBindVertexArray(0);
    }

    void release() {
        GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
        GLES30.glDeleteBuffers(2, new int[]{vertexBuffer, instanceBuffer}, 0);
        vao = vertexBuffer = instanceBuffer = 0;
    }

    private static FloatBuffer buffer(float[] f) {
        FloatBuffer b = ByteBuffer.allocateDirect(f.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(f).position(0); return b;
    }

    static float[] cube() {
        float[] p = {
            -1,-1,1, 1,-1,1, 1,1,1, -1,1,1,
            1,-1,-1, -1,-1,-1, -1,1,-1, 1,1,-1,
            1,-1,1, 1,-1,-1, 1,1,-1, 1,1,1,
            -1,-1,-1, -1,-1,1, -1,1,1, -1,1,-1,
            -1,1,1, 1,1,1, 1,1,-1, -1,1,-1,
            -1,-1,-1, 1,-1,-1, 1,-1,1, -1,-1,1
        };
        float[] n = {0,0,1, 0,0,-1, 1,0,0, -1,0,0, 0,1,0, 0,-1,0};
        int[] idx = {0,1,2,0,2,3};
        float[] out = new float[36 * 6]; int at = 0;
        for (int face = 0; face < 6; face++) for (int j : idx) {
            for (int k = 0; k < 3; k++) out[at++] = p[face * 12 + j * 3 + k] * .5f;
            for (int k = 0; k < 3; k++) out[at++] = n[face * 3 + k];
        }
        return out;
    }

    static float[] floor() {
        final int nx = 52, nz = 68;
        float[] out = new float[nx * nz * 36]; int at = 0;
        int[] corners = {0,2,1,0,3,2};
        for (int z = 0; z < nz; z++) for (int x = 0; x < nx; x++) {
            for (int c : corners) {
                out[at++] = -23f + (x + ((c == 1 || c == 2) ? 1 : 0)) * 46f / nx;
                out[at++] = .005f;
                out[at++] = -43f + (z + ((c == 2 || c == 3) ? 1 : 0)) * 66f / nz;
                out[at++] = 0; out[at++] = 1; out[at++] = 0;
            }
        }
        return out;
    }

    static float[] floorInstance() {
        Builder b = new Builder(); b.box(0,0,0,1,1,1,0,.06f,.08f,.09f,.08f,.55f,0,1); return b.data();
    }

    static float[] architecture() {
        Builder b = new Builder();
        // Raised roof deck and dark parapets frame an open, storm-lit courtyard.
        b.box(0,-.48f,-10,46,.92f,66,0,.065f,.080f,.085f,.1f,.8f,0,0);
        b.box(-13,1.0f,-15,.6f,2.0f,51,0,.075f,.092f,.10f,.6f,.46f,0,0);
        b.box(13,.65f,-15,.6f,1.3f,51,0,.085f,.096f,.10f,.5f,.48f,0,0);
        // Tall colonnade on the left; ceiling beams and roof establish strong parallax.
        for (int i = 0; i < 6; i++) {
            float z = 6 - i * 7;
            b.box(-8.6f,4.0f,z,.65f,8,.72f,0,.105f,.13f,.145f,.68f,.33f,0,0);
            b.box(-8.6f,.20f,z,1.02f,.40f,1.06f,0,.085f,.11f,.12f,.45f,.5f,0,0);
            b.box(-10.7f,7.9f,z,4.7f,.36f,.58f,0,.085f,.105f,.12f,.65f,.38f,0,0);
            b.box(-8.19f,4.2f,z-.04f,.035f,5.6f,.085f,0,.08f,.6f,.68f,.2f,.22f,2.4f,2);
        }
        b.box(-11.1f,8.23f,-11,5.3f,.35f,43,0,.055f,.072f,.09f,.5f,.5f,0,0);
        b.box(-12.9f,4,-12,.40f,8,45,0,.06f,.077f,.085f,.3f,.72f,0,0);
        // Right-side utility block with warm recessed doorway, pipes and service gantry.
        b.box(10.1f,2.4f,-15,5.0f,4.8f,18,0,.11f,.125f,.125f,.22f,.68f,0,0);
        b.box(7.53f,2.2f,-11,.10f,3.6f,2.0f,0,.018f,.025f,.028f,.60f,.3f,0,0);
        b.box(7.42f,3.95f,-11,.12f,.12f,2.3f,0,1f,.44f,.12f,.10f,.20f,4.5f,2);
        b.box(7.46f,2.2f,-9.92f,.10f,3.5f,.08f,0,.80f,.32f,.07f,.10f,.25f,1.4f,2);
        for (int i = 0; i < 12; i++) b.box(7.48f,1.0f+i*.25f,-16.5f,.15f,.08f,3.2f,0,.075f,.09f,.10f,.75f,.36f,0,0);
        for (int i = 0; i < 3; i++) {
            b.box(9+i*.8f,5.1f,-17,.32f,.55f,6,0,.17f,.19f,.20f,.75f,.3f,0,0);
            b.box(9+i*.8f,5.7f,-19.7f,.32f,1.1f,.32f,0,.17f,.19f,.20f,.75f,.3f,0,0);
        }
        // Fine railings at the exposed roof edge.
        for (int i = 0; i < 13; i++) {
            float z = 6-i*3.5f;
            b.box(11.9f,1.1f,z,.09f,2.1f,.09f,0,.26f,.29f,.29f,.8f,.24f,0,0);
        }
        for (int i = 0; i < 3; i++) b.box(11.9f,.48f+i*.64f,-15,.075f,.075f,43,0,.20f,.24f,.25f,.85f,.25f,0,0);
        // The far service gate is a luminous cyan focal point behind drifting rain.
        b.box(-.9f,2.85f,-27.5f,8.7f,5.7f,.5f,0,.038f,.065f,.077f,.5f,.54f,0,0);
        b.box(-.9f,2.5f,-27.12f,4.5f,4.4f,.18f,0,.012f,.026f,.035f,.4f,.35f,0,0);
        b.box(-3.18f,2.55f,-26.96f,.10f,4.5f,.10f,0,.08f,.64f,.78f,.15f,.2f,4.5f,2);
        b.box(1.38f,2.55f,-26.96f,.10f,4.5f,.10f,0,.08f,.64f,.78f,.15f,.2f,4.5f,2);
        b.box(-.9f,4.82f,-26.96f,4.65f,.10f,.10f,0,.08f,.64f,.78f,.15f,.2f,4.5f,2);
        b.box(-.9f,.06f,-25.9f,5.8f,.10f,2.9f,0,.12f,.16f,.18f,.75f,.24f,0,0);
        // A staggered flight of metal steps beside the gate.
        for (int i = 0; i < 9; i++) b.box(5.1f,.12f+i*.22f,-21.6f-i*.47f,2.4f,.22f,.54f,0,.15f,.19f,.21f,.8f,.26f,0,0);
        // Foreground bollards and their small lamps reflect as real point lights on the floor.
        b.box(-4.0f,.65f,-3.2f,.46f,1.3f,.46f,0,.095f,.13f,.145f,.85f,.25f,0,0);
        b.box(-4.0f,1.30f,-3.2f,.48f,.14f,.48f,0,.06f,.70f,.90f,.2f,.18f,4,2);
        b.box(5.3f,.65f,-7.4f,.46f,1.3f,.46f,0,.15f,.13f,.09f,.8f,.26f,0,0);
        b.box(5.3f,1.30f,-7.4f,.48f,.14f,.48f,0,1f,.44f,.11f,.2f,.18f,4,2);
        // Distant skyline: discrete geometry, fogged by distance, with sparse windows.
        for (int i = 0; i < 19; i++) {
            float x = -42+i*4.7f; float h = 5.5f+(float)(Math.sin(i*8.31)*.5+.5)*14;
            float z = -48-(i%3)*5;
            b.box(x,h*.5f-1,z,3.7f,h,5.2f,(i%3-1)*3,.04f,.058f,.073f,.25f,.8f,0,0);
            if (i%3 != 0) for (int j = 0; j < 4; j++) b.box(x-.9f+j*.55f,h*.68f,z+2.63f,.12f,.44f,.03f,0,.24f,.36f,.39f,.1f,.5f,.65f,2);
        }
        // Drainage grates and restrained amber lane markings add scale close to the viewer.
        for (int i = 0; i < 22; i++) b.box(-6.2f,.025f,8-i*1.7f,.52f,.025f,.14f,0,.024f,.034f,.039f,.8f,.45f,0,0);
        for (int i = 0; i < 6; i++) b.box(6.3f,.021f,3-i*4.4f,.075f,.018f,1.1f,0,.33f,.23f,.075f,.05f,.8f,0,0);
        return b.data();
    }

    private static final class Builder {
        final ArrayList<Float> floats = new ArrayList<>();
        final float[] model = new float[16];
        void box(float x,float y,float z,float sx,float sy,float sz,float angle,float r,float g,float b,float metal,float rough,float emission,float type) {
            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,y,z);
            if (angle != 0) Matrix.rotateM(model,0,angle,0,1,0);
            Matrix.scaleM(model,0,sx,sy,sz);
            for (float f : model) floats.add(f);
            floats.add(r); floats.add(g); floats.add(b); floats.add(metal);
            floats.add(rough); floats.add(emission); floats.add(type); floats.add(0f);
        }
        float[] data() { float[] out = new float[floats.size()]; for (int i=0;i<out.length;i++) out[i]=floats.get(i); return out; }
    }
}
