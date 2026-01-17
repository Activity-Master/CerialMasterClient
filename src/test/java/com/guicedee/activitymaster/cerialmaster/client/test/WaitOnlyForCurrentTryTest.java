package com.guicedee.activitymaster.cerialmaster.client.test;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.cerial.enumerations.ComPortType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class WaitOnlyForCurrentTryTest {

    static class MockConnection extends ComPortConnection<MockConnection> {
        public MockConnection() {
            super(20, ComPortType.Device);
        }

        @Override
        public MockConnection connect() {
            return this;
        }

        @Override
        public MockConnection disconnect() {
            return this;
        }
    }

    @Test
    public void testWaitOnlyForCurrentTry() throws Exception {
        MockConnection conn = new MockConnection();

        // Config: 2 retries, 1000ms delay, 5000ms timeout
        // waitOnlyForCurrentTry = true
        Config cfg = new Config(2, 1000, 5000, false, true);
        TimedComPortSender sender = new TimedComPortSender(conn, cfg);

        // Mock attempt function that takes 500ms
        sender.setAttemptFn((connection, attempt) -> {
            System.out.println("[DEBUG_LOG] Attempt " + attempt + " started");
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                System.out.println("[DEBUG_LOG] Attempt " + attempt + " finished");
                return true;
            });
        });

        AtomicReference<TimedComPortSender.State> lastState = new AtomicReference<>();
        sender.status().subscribe().with(update -> {
            System.out.println("[DEBUG_LOG] State changed to: " + update.getState());
            lastState.set(update.getState());
        });

        sender.start("TEST");
        Thread.sleep(200); // Wait for first attempt to start

        // At this point, it should be in Running state (first attempt)
        assertEquals(TimedComPortSender.State.Running, lastState.get());

        System.out.println("[DEBUG_LOG] Calling complete()");
        // Call complete() while first attempt is ongoing
        sender.complete();

        // It should still be Running because waitOnlyForCurrentTry is true and an attempt is in progress
        assertEquals(TimedComPortSender.State.Running, lastState.get());

        // Wait for the first attempt to finish. 
        Thread.sleep(1500); 

        // Now it should be Completed (not TimedOut, because it was externally completed)
        assertEquals(TimedComPortSender.State.Completed, lastState.get());
    }

    @Test
    public void testWaitOnlyForCurrentTry_CompleteBetweenAttempts() throws Exception {
        MockConnection conn = new MockConnection();

        // Config: 2 retries, 500ms delay, 5000ms timeout
        // waitOnlyForCurrentTry = true
        Config cfg = new Config(2, 500, 5000, false, true);
        TimedComPortSender sender = new TimedComPortSender(conn, cfg);

        // Mock attempt function that is fast
        sender.setAttemptFn((connection, attempt) -> CompletableFuture.completedStage(false)); // Fails to trigger retry

        AtomicReference<TimedComPortSender.State> lastState2 = new AtomicReference<>();
        sender.status().subscribe().with(update -> lastState2.set(update.getState()));

        sender.start("TEST");
        
        // Wait for first attempt to finish and be in the delay period before second attempt
        Thread.sleep(100); // attempt 0 done, now waiting 500ms
        
        // At this point, it should be Running (waiting for next attempt)
        // inAttempt should be false
        assertEquals(TimedComPortSender.State.Running, lastState2.get());
        
        sender.complete();

        // It should be Completed immediately because it's between attempts
        Thread.sleep(100);
        assertEquals(TimedComPortSender.State.Completed, lastState2.get());
    }
}
