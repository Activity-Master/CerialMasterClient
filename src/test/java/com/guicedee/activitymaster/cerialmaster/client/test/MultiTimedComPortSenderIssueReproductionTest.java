package com.guicedee.activitymaster.cerialmaster.client.test;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.activitymaster.cerialmaster.client.services.ICerialMasterService;
import com.guicedee.client.IGuiceContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class MultiTimedComPortSenderIssueReproductionTest {

    @Test
    public void testNewGroupStopsOldGroup() throws Exception {
        ICerialMasterService<?> svc = IGuiceContext.get(ICerialMasterService.class);
        try {
            svc.getComPortConnectionDirect(20).await().atMost(Duration.ofSeconds(50));
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "Serial not available or blocked: " + t.getMessage());
            return;
        }

        MultiTimedComPortSender manager = new MultiTimedComPortSender();
        Config cfg = new Config(0, 1000, 5000); // long delay to keep it running

        MessageSpec m1 = new MessageSpec("m1", "P1", cfg);
        MessageSpec m2 = new MessageSpec("m2", "P2", cfg);

        AtomicInteger m1Completed = new AtomicInteger(0);
        
        manager.enqueueGroup(20, List.of(m1), cfg);
        
        Thread.sleep(100);
        
        // Enqueue new group, should stop m1
        manager.enqueueGroup(20, List.of(m2), cfg);
        
        Thread.sleep(100);
        
        TimedComPortSender sender = manager.getSenders().get(20);
        MessageSpec active = sender.getActiveMessageSpec();
        
        assertNotNull(active);
        assertEquals("m2", active.getId(), "m2 should be active, m1 should have been cancelled/stopped");
    }

    @Test
    public void testListenersResetOnNewEnqueue() throws Exception {
        ICerialMasterService<?> svc = IGuiceContext.get(ICerialMasterService.class);
        try {
            svc.getComPortConnectionDirect(20).await().atMost(Duration.ofSeconds(50));
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "Serial not available or blocked: " + t.getMessage());
            return;
        }

        MultiTimedComPortSender manager = new MultiTimedComPortSender();
        Config cfg = new Config(0, 10, 100);

        AtomicInteger completionCount = new AtomicInteger(0);
        manager.onGroupCompleted(progress -> completionCount.incrementAndGet());

        manager.enqueueGroup(20, List.of(new MessageSpec("m1", "P1", cfg)), cfg);
        
        // Complete it
        Thread.sleep(200);
        manager.getSenders().get(20).complete();
        Thread.sleep(100);
        
        assertEquals(1, completionCount.get(), "First group should have completed");

        // Enqueue second group, listener should be reset
        manager.enqueueGroup(20, List.of(new MessageSpec("m2", "P2", cfg)), cfg);
        
        Thread.sleep(200);
        manager.getSenders().get(20).complete();
        Thread.sleep(100);

        assertEquals(1, completionCount.get(), "Listener should have been reset and NOT called for second group");
    }
}
