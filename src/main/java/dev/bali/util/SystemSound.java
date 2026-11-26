package dev.bali.util;

import dev.bali.Bali;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SystemSound {
    private SystemSound() {}

    private static final float RATE = 44_100f;
    private static final double[] NOTES = {880, 1174.7, 1568};
    private static final AtomicBoolean playing = new AtomicBoolean();

    public static volatile int played;
    public static volatile String lastError;

    public static void alarm(double volume, int repeats) {
        if (!playing.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                play(Math.max(0, Math.min(1, volume)), Math.max(1, repeats));
                lastError = null;
                played++;
            } catch (Throwable e) {
                lastError = e.toString();
                Bali.LOG.warn("Bali alarm could not play: {}", e.toString());
            } finally {
                playing.set(false);
            }
        }, "Bali-Alarm");
        t.setDaemon(true);
        t.start();
    }

    private static void play(double volume, int repeats) throws LineUnavailableException {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, (int) RATE / 5 * 2);
            line.start();
            for (int r = 0; r < repeats; r++) {
                for (double f : NOTES) {
                    write(line, tone(f, 0.14, volume));
                    write(line, new byte[samples(0.05) * 2]);
                }
                write(line, new byte[samples(0.25) * 2]);
            }
            line.drain();
        }
    }

    private static int samples(double seconds) { return (int) (RATE * seconds); }

    private static byte[] tone(double freq, double seconds, double volume) {
        int n = samples(seconds), fade = samples(0.005);
        byte[] out = new byte[n * 2];
        double amp = volume * 32767 * 0.85;
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double w = 2 * Math.PI * freq * t;
            double v = (Math.sin(w) + 0.30 * Math.sin(3 * w) + 0.12 * Math.sin(5 * w)) / 1.42;
            double env = Math.min(1, Math.min(i, n - 1 - i) / (double) fade);
            int s = (int) Math.round(v * env * amp);
            out[i * 2] = (byte) s;
            out[i * 2 + 1] = (byte) (s >> 8);
        }
        return out;
    }

    private static void write(SourceDataLine line, byte[] data) {
        line.write(data, 0, data.length);
    }
}
