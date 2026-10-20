package me.kavishdevar.librepods.utils;

import java.io.BufferedReader;
import java.io.IOException;

/** The input pipe is an ownership lease, kept open until the caller cancels or dies. */
final class RootCommandInput {
    interface Trigger { void run(int pulse, int restore); }

    static void readUntilClosed(BufferedReader reader, boolean expectsTrigger, Trigger trigger)
            throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String[] fields = line.trim().split("\\s+");
            if (!expectsTrigger || fields.length != 3 || !"go".equals(fields[0])) {
                throw new IOException("Invalid AVRCP command");
            }
            int pulse = volume(fields[1]);
            int restore = volume(fields[2]);
            expectsTrigger = false;
            trigger.run(pulse, restore);
            // Keep reading after go: EOF must also cancel an active playback wait.
        }
    }

    private static int volume(String value) throws IOException {
        try {
            int volume = Integer.parseInt(value);
            if (volume >= 0 && volume <= 255) return volume;
        } catch (NumberFormatException ignored) { }
        throw new IOException("Invalid AVRCP volume");
    }
}
