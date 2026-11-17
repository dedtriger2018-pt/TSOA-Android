#!/usr/bin/env python3
"""Exercise the production FG clock with deterministic source and display timelines.

Uses Python 3 and JDK 17+. No Android runtime, GL stubs, or wall-clock sleeps are needed.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile


HARNESS = r'''
import com.winlator.renderer.FrameGenerationClock;
import com.winlator.renderer.FrameGenerationClock.Presentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TsoaFrameClockCheck {
    private static final long SECOND = 1_000_000_000L;
    private static final long MS = 1_000_000L;
    private static final long DISPLAY = Math.round(SECOND / 60.0);

    private static void require(boolean value, String text) {
        if (!value) throw new AssertionError(text);
    }

    private static final class Sample {
        final long time;
        final double position;
        final boolean generated;
        Sample(long time, double position, boolean generated) {
            this.time = time;
            this.position = position;
            this.generated = generated;
        }
    }

    /** The two texture identities the real renderer retains, with a fake monotonic clock. */
    private static final class Timeline {
        final FrameGenerationClock clock;
        final List<Sample> samples = new ArrayList<>();
        final Set<Long> real = new HashSet<>();
        final Set<String> generatedPairs = new HashSet<>();
        long previous = -1L;
        long current = -1L;
        double lastPosition = -1.0;
        long lastTime = Long.MIN_VALUE;
        Timeline(long displayPeriod) { clock = new FrameGenerationClock(displayPeriod); }
        boolean source(long time, long serial) {
            if (!clock.onSourceFrame(time, serial)) return false;
            previous = current;
            current = serial;
            return true;
        }
        Presentation present(long now) {
            Presentation frame = clock.getPresentation(now);
            if (!frame.draw) return frame;
            require(now > lastTime, "More than one presentation in a display slot");
            lastTime = now;
            require(frame.fraction >= 0 && frame.fraction <= 1, "Extrapolated a frame");
            double position;
            if (frame.interpolate) {
                require(previous >= 0 && current > previous, "Interpolated without two distinct sources");
                require(frame.fraction == 0.5f, "2x must use only one midpoint");
                require(generatedPairs.add(previous + ":" + current), "Repeated a pair's midpoint");
                position = (previous + current) * 0.5;
            }
            else {
                require(frame.fraction == 0 || frame.fraction == 1, "Unexpected real selection");
                long identity = frame.fraction == 0 ? previous : current;
                require(identity >= 0, "Selected nonexistent previous texture");
                require(real.add(identity), "Presented one real source twice: " + identity);
                position = identity;
            }
            require(position > lastPosition, "Presentation moved backward across a texture shift");
            lastPosition = position;
            samples.add(new Sample(now, position, frame.interpolate));
            require(!clock.getPresentation(now).draw, "Repeated presentation at the same vsync");
            return frame;
        }
    }

    private static long at(double fps, int index) { return Math.round(index * SECOND / fps); }

    private static Timeline stable(double sourceFps, double refresh, int seconds, long[] jitter) {
        long display = Math.round(SECOND / refresh);
        Timeline t = new Timeline(display);
        int lastSource = (int)(sourceFps * seconds);
        int source = 0;
        long until = seconds * SECOND + Math.round(3 * SECOND / sourceFps);
        for (int tick = 0; at(refresh, tick) <= until; tick++) {
            long now = at(refresh, tick);
            int latest = -1;
            long timestamp = 0;
            while (source <= lastSource) {
                long candidate = at(sourceFps, source)
                        + (jitter == null || source == 0 ? 0 : jitter[source % jitter.length]);
                if (candidate > now) break;
                latest = source++;
                timestamp = candidate;
            }
            if (latest >= 0) t.source(timestamp, latest + 1L);
            t.present(now);
        }
        require(!t.clock.wantsVsync(until), "Static game retained a vsync loop");
        return t;
    }

    private static void stableRates() {
        Timeline thirty = stable(30, 60, 3, null);
        require(thirty.real.size() >= 90, "30 FPS lost real frames");
        require(thirty.generatedPairs.size() >= 85, "30 FPS did not approach stable 2x");
        require(thirty.generatedPairs.size() <= 90, "More than one midpoint per real pair");
        // Once warm, 30 -> 60 occupies each display slot, including real frames arriving
        // exactly when the two retained source textures shift to their next pair.
        long prior = -1;
        for (Sample s : thirty.samples) if (s.time > SECOND && s.time < 3 * SECOND) {
            if (prior >= 0) require(Math.abs(s.time - prior - DISPLAY) <= 2,
                    "30 -> 60 missed a display slot after warmup");
            prior = s.time;
        }

        Timeline twenty = stable(20, 60, 3, null);
        require(twenty.real.size() >= 60 && twenty.generatedPairs.size() >= 55,
                "20 -> 40 did not retain real frames and one midpoint");
        require(twenty.samples.size() <= 122, "20 FPS exceeded the 2x cap");
        Timeline ten = stable(10, 60, 3, null);
        require(ten.generatedPairs.size() >= 25, "Arbitrary low FPS was incorrectly gated at 20");

        Timeline fortyFive = stable(45, 60, 4, null);
        require(fortyFive.real.size() >= 179, "45 -> 60 starved real frames for interpolation");
        require(fortyFive.generatedPairs.size() >= 40, "45 FPS never used spare display slots");
        require(fortyFive.generatedPairs.size() <= 65, "45 FPS exceeded display capacity");
        Timeline sixty = stable(60, 60, 3, null);
        require(sixty.generatedPairs.isEmpty(), "60 FPS incurred generation work on a 60 Hz panel");
        require(!sixty.clock.isInterpolationEligible(), "60 FPS remained eligible for FG");
        Timeline fast = stable(120, 60, 3, null);
        require(fast.generatedPairs.isEmpty(), "Faster-than-display sources generated extra frames");
    }

    private static void jitterAndCoalescing() {
        Timeline jitter = stable(30, 60, 4, new long[]{0, 2 * MS, -MS, MS, -2 * MS});
        require(jitter.real.size() >= 118, "Arrival jitter starved real frames");
        require(jitter.generatedPairs.size() >= 70, "Arrival jitter disabled useful generation: " + jitter.generatedPairs.size());
        // All 60 textures remain observable here. Alternating arrival times on opposite
        // sides of vsync can instead coalesce to 30 captures; latest-only capture timing
        // cannot recover the unseen frames' rate from a serial that counts damage events.
        Timeline sixtyJitter = stable(60, 60, 3, new long[]{-2 * MS, 0});
        require(sixtyJitter.generatedPairs.isEmpty(), "Jitter mistook a 60 FPS source for spare capacity");

        Timeline t = new Timeline(DISPLAY);
        int next = 0;
        boolean resumed = false;
        for (int tick = 0; tick <= 100; tick++) {
            long now = at(60, tick);
            // Simulate several source changes arriving while GL is busy. Only the latest
            // complete texture survives; serial gaps must not masquerade as retained frames.
            if (now >= 450 * MS && now < 570 * MS) continue;
            int latest = -1;
            while (at(30, next) <= now) latest = next++;
            if (latest >= 0) {
                t.source(at(30, latest), 10L * (latest + 1));
                if (now >= 570 * MS && !resumed) {
                    resumed = true;
                    require(!t.clock.isInterpolationEligible(), "Warped across a coalesced long capture gap");
                    require(!t.present(now).interpolate, "Long capture gap did not fall back to a real frame");
                    continue;
                }
            }
            t.present(now);
        }
        require(resumed && t.generatedPairs.size() > 25, "Coalesced updates did not recover");
    }

    private static void transitionsAndRefresh() {
        Timeline t = new Timeline(DISPLAY);
        int firstFast = 0;
        for (int tick = 0; tick <= 180; tick++) {
            long now = at(60, tick);
            if (now <= SECOND) {
                if (tick % 2 == 0) t.source(now, tick / 2L + 1);
            }
            else {
                t.source(now, 31L + ++firstFast);
                if (firstFast >= 2) require(!t.clock.isInterpolationEligible(),
                        "30 -> 60 kept doing FG after the rate increased");
            }
            t.present(now);
        }
        int before = t.generatedPairs.size();
        require(before > 20, "Rate transition test never established generation");
        require(t.samples.get(t.samples.size() - 1).position == t.current,
                "60 FPS transition retained delayed content");

        Timeline twentyToForty = new Timeline(DISPLAY);
        int serial = 0;
        for (int tick = 0; tick < 240; tick++) {
            long now = at(60, tick);
            if (now <= SECOND) {
                if (tick % 3 == 0) twentyToForty.source(now, ++serial);
            }
            else {
                long previousNow = at(60, tick - 1);
                int latest = (int)((now - SECOND) / (25 * MS));
                int prior = (int)((previousNow - SECOND) / (25 * MS));
                if (latest > prior) twentyToForty.source(SECOND + latest * 25 * MS, ++serial);
            }
            twentyToForty.present(now);
        }
        require(twentyToForty.generatedPairs.size() > 50, "20 -> 40 transition stopped generation");
        require(Math.abs(twentyToForty.clock.getSourcePeriodNanos() - 25 * MS) < MS,
                "20 -> 40 source estimate did not adapt");

        Timeline refresh = stable(60, 60, 1, null);
        refresh.clock.setDisplayPeriodNanos(Math.round(SECOND / 120.0));
        require(refresh.source(at(60, 61), 62), "Refresh transition rejected new content");
        require(refresh.clock.isInterpolationEligible(), "120 Hz panel did not allow 60 -> 120");
        refresh.clock.setDisplayPeriodNanos(DISPLAY);
        require(!refresh.clock.isInterpolationEligible(), "60 Hz refresh did not clear over-capacity work");
    }

    private static void invalidHistoryAndStall() {
        Timeline t = new Timeline(DISPLAY);
        for (int i = 0; i <= 3; i++) {
            t.source(i * 33 * MS, i + 1);
            t.present(i * 33 * MS);
        }
        require(t.clock.isInterpolationEligible(), "Warmup never became eligible");
        long estimate = t.clock.getSourcePeriodNanos();
        require(!t.source(9 * SECOND, 4), "Duplicate serial accepted a fresh timestamp");
        require(!t.source(9 * SECOND, 3), "Out-of-order serial accepted");
        require(t.clock.getSourcePeriodNanos() == estimate, "Duplicate content changed cadence");
        require(t.clock.wantsVsync(100 * MS), "Pending pair failed to request a vsync");
        require(t.present(116 * MS).interpolate, "Pending midpoint was not presented before real");
        require(t.present(133 * MS).fraction == 1, "Pending current real was not drained");
        require(!t.clock.wantsVsync(10 * SECOND), "Stalled game generated indefinitely");
        for (int i = 0; i < 20; i++) require(!t.present(SECOND + i * DISPLAY).draw,
                "Stall repeated generated or real presentations");

        // A late GL draw skips the missed midpoint and uses the newest complete real frame.
        t.source(132 * MS, 5);
        require(!t.present(200 * MS).interpolate, "Late frame replayed a missed midpoint");
        require(!t.clock.wantsVsync(200 * MS), "Late frame left a generation backlog");

        t.source(382 * MS, 6); // Exactly the 250 ms stale threshold.
        require(!t.clock.isInterpolationEligible() && t.clock.getSourcePeriodNanos() == 0,
                "Stale boundary retained cadence/history");
        require(t.present(382 * MS).fraction == 1, "Stale source failed to present current only");

        t.source(381 * MS, 7);
        require(!t.clock.isInterpolationEligible() && t.clock.getSourcePeriodNanos() == 0,
                "Backwards source time did not invalidate history");
        require(!t.present(381 * MS).draw, "Backwards display time moved the presentation timeline");
        require(t.present(400 * MS).fraction == 1, "Bad timestamp lost new real content");
        t.clock.reset();
        require(!t.clock.wantsVsync(3 * SECOND) && !t.clock.getPresentation(3 * SECOND).draw,
                "Resize/scene/pause reset left scheduled work");
        require(t.clock.onSourceFrame(3 * SECOND, 1), "Reset retained stale source identity");
        require(!t.clock.isInterpolationEligible() && t.clock.getPresentation(3 * SECOND).fraction == 1,
                "Reset interpolated across unrelated texture history");
    }

    private static void normalRenderWarmup() {
        FrameGenerationClock clock = new FrameGenerationClock(DISPLAY);
        for (int i = 0; i <= 3; i++) {
            long now = i * 33 * MS;
            clock.onSourceFrame(now, i + 1);
            // Warmup was drawn normally; the first eligible capture also seeds only one
            // history texture. Neither should leave a synthetic midpoint on the UI queue.
            clock.markCurrentPresented(now);
            require(!clock.wantsVsync(now), "Normal-render warmup retained fake pending work");
            require(!clock.getPresentation(now + DISPLAY).draw, "Normal-render warmup repeated a real");
        }
        require(clock.isInterpolationEligible(), "Normal rendering discarded cadence confidence");
        require(Math.abs(clock.getSourcePeriodNanos() - 33 * MS) < MS,
                "Normal-render acknowledgement changed the source estimate");
        clock.onSourceFrame(132 * MS, 5);
        require(clock.isInterpolationEligible(), "Next complete texture pair lost eligibility");
        require(!clock.getPresentation(132 * MS).draw, "Next pair replayed an already shown previous real");
        require(clock.getPresentation(149 * MS).interpolate, "Next valid pair did not interpolate");
        Presentation real = clock.getPresentation(166 * MS);
        require(real.draw && real.fraction == 1 && !clock.wantsVsync(166 * MS),
                "Next valid pair failed to drain its real frame");
    }

    public static void main(String[] args) {
        stableRates();
        jitterAndCoalescing();
        transitionsAndRefresh();
        invalidHistoryAndStall();
        normalRenderWarmup();
        System.out.println("PASS: production FG clock; 30->60, 20->40, low FPS, 45->60 ceiling, "
                + "60+ bypass, jitter/coalescing, unique midpoint cap, real-frame order, "
                + "rate/refresh changes, stale/late fallback, duplicate IDs, resets and static stop");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    production = root / 'app/src/main/java/com/winlator/renderer/FrameGenerationClock.java'
    with tempfile.TemporaryDirectory(prefix='tsoa-frame-clock-') as directory:
        temporary = Path(directory)
        harness = temporary / 'TsoaFrameClockCheck.java'
        harness.write_text(HARNESS, encoding='utf-8')
        classes = temporary / 'classes'
        classes.mkdir()
        subprocess.run([args.java, '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                        '-source', '8', '-target', '8', '-Xlint:-options', '-d', str(classes),
                        str(production), str(harness)], check=True)
        subprocess.run([args.java, '-cp', str(classes), 'TsoaFrameClockCheck'], check=True)


if __name__ == '__main__':
    main()
