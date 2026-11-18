package com.winlator.renderer;

/** Estimates compositor load from source damage and CPU/driver submission time. */
public final class GraphicsBudget {
    public static final class Sample {
        public final float sourceRate, generatedRate, submissionMs;
        public final int quality;
        public final long atNanos;

        Sample(float sourceRate, float generatedRate, float submissionMs, int quality, long atNanos) {
            this.sourceRate = sourceRate;
            this.generatedRate = generatedRate;
            this.submissionMs = submissionMs;
            this.quality = quality;
            this.atNanos = atNanos;
        }
    }

    private static final long WINDOW = 2_000_000_000L;
    private final boolean adaptive;
    private volatile int thermal;
    private volatile Sample sample;
    private int quality = 3, coolWindows, pressureWindows, sourceFrames, draws, generated, slow;
    private long started, sourceTime, totalCost;

    GraphicsBudget(boolean adaptive) {
        this.adaptive = adaptive;
    }

    boolean isAdaptive() { return adaptive; }
    public void setThermal(int status) { thermal = status; }
    public Sample sample() { return sample; }

    void noteGenerated() { generated++; }

    int frame(long now, long source, long cost, long displayPeriod) {
        if (started == 0) started = now;
        if (source != sourceTime) {
            sourceTime = source;
            sourceFrames++;
        }
        draws++;
        totalCost += cost;
        if (cost > displayPeriod * 0.85) slow++;
        long elapsed = now - started;
        if (elapsed < WINDOW) return quality;

        if (adaptive) {
            boolean busy = draws >= 12 && slow * 4 >= draws;
            boolean cool = thermal < 2 && draws >= 12 && slow * 16 < draws;
            pressureWindows = busy || thermal >= 3 ? pressureWindows + 1 : 0;
            coolWindows = cool ? coolWindows + 1 : 0;
            if (thermal >= 4 || pressureWindows >= 2) {
                quality = thermal >= 4 ? 1 : Math.max(1, quality - 1);
                coolWindows = pressureWindows = 0;
            } else if (coolWindows >= 4) {
                quality = Math.min(3, quality + 1);
                coolWindows = 0;
            }
        }
        float seconds = elapsed / 1_000_000_000f;
        sample = new Sample(sourceFrames / seconds, generated / seconds, totalCost / (float)draws / 1_000_000f, quality, now);
        started = now;
        sourceFrames = draws = generated = slow = 0;
        totalCost = 0;
        return quality;
    }
}
