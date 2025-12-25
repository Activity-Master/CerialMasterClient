package com.guicedee.activitymaster.cerialmaster.client.test;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.activitymaster.cerialmaster.client.services.ICerialMasterService;
import com.guicedee.client.IGuiceContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class StatisticsUpdatedReproductionTest {

    @Test
    public void testMultipleConsumersFire() throws Exception {
        MultiTimedComPortSender manager = new MultiTimedComPortSender();
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        manager.onStatisticsUpdated(snapshot -> latch1.countDown());
        manager.onStatisticsUpdated(snapshot -> latch2.countDown());

        manager.setGroupName("TestGroupMultiple");

        boolean received1 = latch1.await(3, TimeUnit.SECONDS);
        boolean received2 = latch2.await(3, TimeUnit.SECONDS);

        assertTrue(received1, "Consumer 1 should have received update");
        assertTrue(received2, "Consumer 2 should have received update");
    }

    @Test
    public void testImmediateUpdateOnRegistration() throws Exception {
        MultiTimedComPortSender manager = new MultiTimedComPortSender();
        manager.setGroupName("PreExistingGroup");
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ManagerSnapshot> snap = new AtomicReference<>();
        
        manager.onStatisticsUpdated(s -> {
            snap.set(s);
            latch.countDown();
        });
        
        boolean received = latch.await(1, TimeUnit.SECONDS);
        assertTrue(received, "Should receive an immediate update upon registration");
        assertNotNull(snap.get());
        assertEquals("PreExistingGroup", snap.get().aggregate.groupName);
    }
}
