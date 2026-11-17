package com.winlator.renderer;

/**
 * Source-frame timing and bounded 2x presentation scheduling, without Android or GL state.
 *
 * A source serial identifies changed game content, not a render callback or a cursor update.
 * Timestamps and vsync times must use the same monotonic clock. The owner keeps the latest
 * two complete source textures; a fraction of zero selects the previous texture, one half
 * requests one interpolated frame, and one selects the current texture. No extrapolation is
 * requested. Call {@link #reset()} whenever those two textures cease to describe one scene.
 */
public final class FrameGenerationClock {
    public static final long DEFAULT_DISPLAY_PERIOD_NANOS = 16_666_667L;
    public static final long STALE_SOURCE_GAP_NANOS = 250_000_000L;

    /** Immutable, shared results avoid allocating an object on every display callback. */
    public static final class Presentation {
        public final boolean draw;
        public final boolean interpolate;
        public final float fraction;

        private Presentation(boolean draw, float fraction) {
            this.draw = draw;
            this.fraction = fraction;
            interpolate = draw && fraction > 0.0f && fraction < 1.0f;
        }
    }

    private static final Presentation HOLD = new Presentation(false, 1.0f);
    private static final Presentation PREVIOUS = new Presentation(true, 0.0f);
    private static final Presentation MIDPOINT = new Presentation(true, 0.5f);
    private static final Presentation CURRENT = new Presentation(true, 1.0f);
    private static final int CONFIDENT_INTERVALS = 3;

    private long displayPeriodNanos;
    private boolean hasSource;
    private long sourceSerial;
    private long sourceTimestampNanos;
    private double sourcePeriodNanos;
    private int observedIntervals;
    private final long[] periodWindow = new long[4];
    private int periodWindowCount;
    private int periodWindowIndex;
    private long periodWindowSumNanos;
    private double rateCandidateNanos;
    private int rateCandidateDirection;
    private boolean cadenceEligible;
    private boolean interpolationEligible;

    private boolean previousPending;
    private boolean midpointPending;
    private boolean currentPending;
    private long previousDueNanos;
    private long midpointDueNanos;
    private long currentDueNanos;
    private long lastPresentationNanos = Long.MIN_VALUE;

    public FrameGenerationClock() {
        this(DEFAULT_DISPLAY_PERIOD_NANOS);
    }

    public FrameGenerationClock(long displayPeriodNanos) {
        this.displayPeriodNanos = validDisplayPeriod(displayPeriodNanos);
    }

    /** Accept a refresh-rate change without inventing a new source frame. */
    public synchronized void setDisplayPeriodNanos(long periodNanos) {
        displayPeriodNanos = validDisplayPeriod(periodNanos);
        updateCadenceEligibility();
        if (!cadenceEligible) {
            interpolationEligible = false;
            previousPending = false;
            midpointPending = false;
            // A newly faster source or slower panel should never wait in a generation queue.
            if (currentPending) currentDueNanos = sourceTimestampNanos;
        }
    }

    /** Discard both cadence confidence and pending work on resize, scene/context change or pause. */
    public synchronized void reset() {
        hasSource = false;
        sourceSerial = 0L;
        sourceTimestampNanos = 0L;
        clearCadence();
        previousPending = false;
        midpointPending = false;
        currentPending = false;
        lastPresentationNanos = Long.MIN_VALUE;
    }

    /**
     * Record a newly captured source texture. Duplicate or older serials do nothing, including
     * leaving the cadence estimate untouched. Serial gaps are not frame counts: several damage
     * events can be coalesced into one captured texture.
     *
     * @return true when this is new content, including a current-only timing fallback
     */
    public synchronized boolean onSourceFrame(long timestampNanos, long serial) {
        if (hasSource && serial <= sourceSerial) return false;
        if (!hasSource) {
            hasSource = true;
            sourceSerial = serial;
            sourceTimestampNanos = timestampNanos;
            scheduleCurrent(timestampNanos);
            return true;
        }

        long intervalNanos = timestampNanos - sourceTimestampNanos;
        long oldTimestampNanos = sourceTimestampNanos;
        long oldCurrentDueNanos = currentDueNanos;
        boolean oldCurrentPending = currentPending;
        sourceSerial = serial;
        // A bad event timestamp must not move the source timeline backwards.
        sourceTimestampNanos = Math.max(timestampNanos, sourceTimestampNanos);
        if (intervalNanos <= 0L || intervalNanos >= STALE_SOURCE_GAP_NANOS) {
            clearCadence();
            scheduleCurrent(sourceTimestampNanos);
            return true;
        }

        observeInterval(intervalNanos);
        updateCadenceEligibility();
        // A short burst leaves no room on the panel. A coalesced/missed capture may span a
        // much larger motion interval than the estimate; present it directly and keep learning.
        interpolationEligible = cadenceEligible
                && intervalNanos > displayPeriodNanos * 1.02
                && intervalNanos >= sourcePeriodNanos * 0.45
                && intervalNanos <= sourcePeriodNanos * 1.8;
        if (!interpolationEligible) {
            scheduleCurrent(sourceTimestampNanos);
            return true;
        }

        long delayNanos = Math.round(sourcePeriodNanos);
        currentDueNanos = sourceTimestampNanos + delayNanos;
        midpointDueNanos = oldTimestampNanos + intervalNanos / 2L + delayNanos;
        previousPending = oldCurrentPending;
        // On a texture shift the old current is now the previous texture. Retain its real
        // presentation deadline, rather than losing it to the next generated midpoint.
        previousDueNanos = Math.min(oldCurrentDueNanos, midpointDueNanos);
        midpointPending = true;
        currentPending = true;
        return true;
    }

    /**
     * Consume at most one presentation per vsync. Missed midpoints are dropped; they are never
     * queued, repeated, or emitted after the corresponding real frame. At refresh-limited
     * source rates the previous real frame has priority over the next midpoint.
     */
    public synchronized Presentation getPresentation(long vsyncNanos) {
        if (!currentPending || vsyncNanos <= lastPresentationNanos) return HOLD;
        if (isDue(vsyncNanos, currentDueNanos)) {
            previousPending = false;
            midpointPending = false;
            currentPending = false;
            lastPresentationNanos = vsyncNanos;
            return CURRENT;
        }
        if (previousPending && isDue(vsyncNanos, previousDueNanos)) {
            previousPending = false;
            // If this vsync was already the midpoint's slot, do not try to catch it up later.
            if (isDue(vsyncNanos, midpointDueNanos)) midpointPending = false;
            lastPresentationNanos = vsyncNanos;
            return PREVIOUS;
        }
        if (midpointPending && isDue(vsyncNanos, midpointDueNanos)) {
            midpointPending = false;
            lastPresentationNanos = vsyncNanos;
            return MIDPOINT;
        }
        return HOLD;
    }

    /**
     * There is only finite work for the latest captured pair. The owner can stop posting vsync
     * callbacks as soon as this is false, and restart when new game content arrives. A stalled
     * source therefore drains its last real frame without an ongoing render loop.
     */
    public synchronized boolean wantsVsync(long nowNanos) {
        return currentPending;
    }

    /**
     * The owner has already displayed the latest real source through its normal-render path
     * or while seeding the first history texture. Cancel that source's pending interpolation
     * without throwing away the cadence learned during warmup.
     */
    public synchronized void markCurrentPresented(long timestampNanos) {
        if (!hasSource) return;
        previousPending = false;
        midpointPending = false;
        currentPending = false;
        lastPresentationNanos = Math.max(lastPresentationNanos, timestampNanos);
    }

    /** Whether the current pair has enough timing confidence and display room for interpolation. */
    public synchronized boolean isInterpolationEligible() {
        return interpolationEligible;
    }

    /** Zero during initial warmup or after invalid/stale source timing. */
    public synchronized long getSourcePeriodNanos() {
        return Math.round(sourcePeriodNanos);
    }

    private void scheduleCurrent(long timestampNanos) {
        previousPending = false;
        midpointPending = false;
        currentPending = true;
        currentDueNanos = timestampNanos;
    }

    private void clearCadence() {
        sourcePeriodNanos = 0.0;
        observedIntervals = 0;
        periodWindowCount = 0;
        periodWindowIndex = 0;
        periodWindowSumNanos = 0L;
        rateCandidateNanos = 0.0;
        rateCandidateDirection = 0;
        cadenceEligible = false;
        interpolationEligible = false;
    }

    private void observeInterval(long intervalNanos) {
        if (periodWindowCount == periodWindow.length) {
            periodWindowSumNanos -= periodWindow[periodWindowIndex];
        }
        else periodWindowCount++;
        periodWindow[periodWindowIndex] = intervalNanos;
        periodWindowSumNanos += intervalNanos;
        periodWindowIndex = (periodWindowIndex + 1) % periodWindow.length;
        if (observedIntervals == 0) {
            sourcePeriodNanos = intervalNanos;
        }
        else {
            int direction = intervalNanos < sourcePeriodNanos * 0.8 ? -1
                    : intervalNanos > sourcePeriodNanos * 1.6 ? 1 : 0;
            if (direction != 0 && direction == rateCandidateDirection
                    && Math.abs(intervalNanos - rateCandidateNanos) <= rateCandidateNanos * 0.2) {
                // Two matching large changes are a rate transition, rather than one late
                // callback. In particular, stop doing generation work promptly at 60+ FPS.
                sourcePeriodNanos = (intervalNanos + rateCandidateNanos) * 0.5;
                rateCandidateDirection = 0;
            }
            else {
                rateCandidateDirection = direction;
                rateCandidateNanos = intervalNanos;
                double bounded = Math.max(sourcePeriodNanos * 0.65,
                        Math.min(sourcePeriodNanos * 1.35, intervalNanos));
                sourcePeriodNanos += (bounded - sourcePeriodNanos) * 0.25;
            }
        }
        if (observedIntervals < CONFIDENT_INTERVALS) observedIntervals++;
    }

    private void updateCadenceEligibility() {
        // Hysteresis prevents a nominal 60 FPS stream with callback jitter from repeatedly
        // allocating motion work on a 60 Hz panel. Lower rates, including below 20 FPS, work.
        double minimumPeriod = displayPeriodNanos * (cadenceEligible ? 1.06 : 1.12);
        cadenceEligible = observedIntervals >= CONFIDENT_INTERVALS
                && sourcePeriodNanos > minimumPeriod
                && periodWindowCount > 0
                && (double)periodWindowSumNanos / periodWindowCount > minimumPeriod;
    }

    private boolean isDue(long nowNanos, long dueNanos) {
        // Choreographer and source timestamps can differ by a rounding tick or small callback
        // jitter. This bounded tolerance prevents a one-nanosecond slip from wasting a vsync.
        long toleranceNanos = Math.min(2_500_000L, displayPeriodNanos / 6L);
        return dueNanos - nowNanos <= toleranceNanos;
    }

    private static long validDisplayPeriod(long periodNanos) {
        return periodNanos > 0L && periodNanos < STALE_SOURCE_GAP_NANOS
                ? periodNanos : DEFAULT_DISPLAY_PERIOD_NANOS;
    }
}
