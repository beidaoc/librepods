package me.kavishdevar.librepods.utils;

import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RootCommandInputTest {
    @Test public void ownerClosureCancelsAfterTrigger() throws Exception {
        PipedWriter owner = new PipedWriter();
        BufferedReader reader = new BufferedReader(new PipedReader(owner));
        CountDownLatch triggered = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = executor.submit(() -> {
                RootCommandInput.readUntilClosed(reader, true, (pulse, restore) -> {
                    assertEquals(20, pulse);
                    assertEquals(21, restore);
                    triggered.countDown();
                });
                return null;
            });
            owner.write("go 20 21\n");
            owner.flush();
            assertTrue(triggered.await(2, TimeUnit.SECONDS));
            assertFalse("Must watch owner after go", task.isDone());
            owner.close();
            task.get(2, TimeUnit.SECONDS);
        } finally {
            owner.close();
            reader.close();
            executor.shutdownNow();
        }
    }

    @Test public void ownerClosureBeforeTriggerDoesNotSendVolume() throws Exception {
        RootCommandInput.readUntilClosed(new BufferedReader(new StringReader("")), true,
                (pulse, restore) -> fail("No trigger was sent"));
    }

    @Test public void invalidVolumesAndRepeatedTriggersAreRejected() throws Exception {
        for (String input : new String[]{"go -1 2\n", "go 1 256\n", "go a 2\n", "go 1 2\ngo 3 4\n"}) {
            try {
                RootCommandInput.readUntilClosed(new BufferedReader(new StringReader(input)), true,
                        (pulse, restore) -> {});
                fail("Accepted invalid input: " + input);
            } catch (IOException expected) { }
        }
    }
}
