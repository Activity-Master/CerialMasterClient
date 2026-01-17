package com.guicedee.activitymaster.cerialmaster.client.test;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.cerial.enumerations.ComPortType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class MultiTimedComPortSenderTimerTest {

    static class MockConnection extends ComPortConnection<MockConnection> {
        public MockConnection(int port) {
            super(port, ComPortType.Device);
        }
        @Override public MockConnection connect() { return this; }
        @Override public MockConnection disconnect() { return this; }
    }

    @Test
    public void testMultipleListenersNotAccumulatedByDefault() throws Exception {
        MultiTimedComPortSender manager = new MultiTimedComPortSender();
        MockConnection conn = new MockConnection(20);
        Config cfg = new Config(1, 100, 100);

        AtomicInteger firstListenerCalls = new AtomicInteger(0);
        AtomicInteger secondListenerCalls = new AtomicInteger(0);

        manager.enqueueGroup(20, List.of(new MessageSpec("1", "P1", cfg)), cfg);

        // Register first listener
        manager.onGroupCompleted(agg -> firstListenerCalls.incrementAndGet());

        // Register second listener (should cancel/replace the first one by default)
        manager.onGroupCompleted(agg -> secondListenerCalls.incrementAndGet());

        // Manually complete the group via the sender
        TimedComPortSender sender = manager.getSenders().get(20);
        sender.complete();

        Thread.sleep(200);

        assertEquals(0, firstListenerCalls.get(), "First listener should have been cancelled/replaced");
        assertEquals(1, secondListenerCalls.get(), "Second listener should have been called");
    }

    @Test
    public void testListenersAccumulatedIfRequested() throws Exception {
        MultiTimedComPortSender manager = new MultiTimedComPortSender();
        MockConnection conn = new MockConnection(20);
        Config cfg = new Config(1, 100, 100);

        AtomicInteger firstListenerCalls = new AtomicInteger(0);
        AtomicInteger secondListenerCalls = new AtomicInteger(0);

        manager.enqueueGroup(20, List.of(new MessageSpec("1", "P1", cfg)), cfg);

        // Register first listener
        manager.onGroupCompleted(agg -> firstListenerCalls.incrementAndGet(), true); // accumulate=true

        // Register second listener
        manager.onGroupCompleted(agg -> secondListenerCalls.incrementAndGet(), true); // accumulate=true

        // Manually complete the group
        TimedComPortSender sender = manager.getSenders().get(20);
        sender.complete();

        Thread.sleep(200);

        assertEquals(1, firstListenerCalls.get(), "First listener should have been called");
        assertEquals(1, secondListenerCalls.get(), "Second listener should have been called");
    }
}
