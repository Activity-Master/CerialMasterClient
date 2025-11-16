package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated manager snapshot that includes current aggregate progress (non-final snapshot)
 * and per-sender snapshots limited as requested (waiting: next 25, sending: 0..1, completed: last 100).
 */
@Getter
@Setter
@NoArgsConstructor
@NgDataType
public final class ManagerSnapshot implements INgDataType<ManagerSnapshot> {
    public AggregateProgress aggregate; // not necessarily finished
    public Map<Integer, SenderSnapshot> perSender; // by COM port

    public ManagerSnapshot(AggregateProgress aggregate,
                           Map<Integer, SenderSnapshot> perSender) {
        this.aggregate = aggregate;
        this.perSender = perSender;
    }

    /**
     * Convenience factory to build a ManagerSnapshot using the given manager's current state
     * with custom limits for waiting and completed lists per sender.
     */
    public static ManagerSnapshot from(MultiTimedComPortSender manager, int waitingLimit, int completedLimit) {
        AggregateProgress agg = manager.snapshot().aggregate;
        Map<Integer, SenderSnapshot> map = new LinkedHashMap<>();
        for (Map.Entry<Integer, com.guicedee.activitymaster.cerialmaster.client.TimedComPortSender> e : manager.getSenders().entrySet()) {
            map.put(e.getKey(), e.getValue().snapshot(waitingLimit, completedLimit));
        }
        return new ManagerSnapshot(agg, Collections.unmodifiableMap(map));
    }
}
