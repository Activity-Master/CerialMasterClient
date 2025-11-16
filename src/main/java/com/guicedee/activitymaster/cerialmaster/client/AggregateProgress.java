package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@NgDataType
@NoArgsConstructor
public class AggregateProgress implements INgDataType<AggregateProgress>
{
    public String groupName;
    public long startedAtEpochMs;
    public Long finishedAtEpochMs; // null until finished
    public int totalPorts;
    public int totalMessages;
    public long totalTasksBudget; // sum of (messages x retries) across ports
    public long tasksRemaining;   // remaining tasks under same definition
    public boolean anyFailures;
    public boolean anyErrored; // explicit roll-up alias: true if any message errored in the group
    public List<Failure> failures; // immutable snapshot
    public double percentCompleteOverall; // 0..100
    public Map<Integer, PortDetail> perPort; // immutable snapshot
    public long maxTimeRemainingMs; // worst-case if all remaining messages timeout
    public long timeRemainingMs; // alias of maxTimeRemainingMs for direct consumption
    public Long estimatedFinishedAtEpochMs; // now + maxTimeRemainingMs (or finishedAt when terminal)
    public Long originallyEstimatedFinishedAtEpochMs; // at run start: startedAt + initial overall worst-case
    public OffsetDateTime startedAt; // UTC
    public OffsetDateTime finishedAt; // UTC
    public OffsetDateTime estimatedFinishedAt; // UTC
    public OffsetDateTime originallyEstimatedFinishedAt; // UTC
    public long timeSavedMs; // cumulative (worst-case minus actual for early completions)
    public Map<String, Boolean> properties; // immutable per-run custom properties
    public boolean anyWaitingForResponse; // roll-up: true if any port currently has an in-flight message
    public Set<Integer> comPorts; // set of COM ports involved in the current group

    public AggregateProgress(String groupName,
                             long startedAtEpochMs,
                             Long finishedAtEpochMs,
                             int totalPorts,
                             int totalMessages,
                             long totalTasksBudget,
                             long tasksRemaining,
                             boolean anyFailures,
                             boolean anyErrored,
                             List<Failure> failures,
                             double percentCompleteOverall,
                             Map<Integer, PortDetail> perPort,
                             long maxTimeRemainingMs,
                             long timeRemainingMs,
                             Long estimatedFinishedAtEpochMs,
                             Long originallyEstimatedFinishedAtEpochMs,
                             long timeSavedMs,
                             Map<String, Boolean> properties,
                             boolean anyWaitingForResponse,
                             Set<Integer> comPorts)
    {
        this.groupName = groupName;
        this.startedAtEpochMs = startedAtEpochMs;
        this.finishedAtEpochMs = finishedAtEpochMs;
        this.totalPorts = totalPorts;
        this.totalMessages = totalMessages;
        this.totalTasksBudget = totalTasksBudget;
        this.tasksRemaining = tasksRemaining;
        this.anyFailures = anyFailures;
        this.anyErrored = anyErrored;
        this.failures = failures;
        this.percentCompleteOverall = percentCompleteOverall;
        this.perPort = perPort;
        this.maxTimeRemainingMs = maxTimeRemainingMs;
        this.timeRemainingMs = timeRemainingMs;
        this.estimatedFinishedAtEpochMs = estimatedFinishedAtEpochMs;
        this.originallyEstimatedFinishedAtEpochMs = originallyEstimatedFinishedAtEpochMs;
        this.startedAt = MultiTimedComPortSender.toOffset(startedAtEpochMs);
        this.finishedAt = MultiTimedComPortSender.toOffset(finishedAtEpochMs);
        this.estimatedFinishedAt = MultiTimedComPortSender.toOffset(estimatedFinishedAtEpochMs);
        this.originallyEstimatedFinishedAt = MultiTimedComPortSender.toOffset(originallyEstimatedFinishedAtEpochMs);
        this.timeSavedMs = timeSavedMs;
        this.properties = properties != null ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(properties)) : java.util.Map.of();
        this.anyWaitingForResponse = anyWaitingForResponse;
        // Ensure comPorts reflects the unique set of COM ports in the current running group.
        // Prefer deriving from perPort (authoritative for the running group); fall back to provided comPorts if perPort is null.
        if (this.perPort != null)
        {
            this.comPorts = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(this.perPort.keySet()));
        }
        else
        {
            this.comPorts = comPorts != null ? java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(comPorts)) : java.util.Set.of();
        }
    }

    // Backward-compatible constructor to preserve existing call sites
    public AggregateProgress(String groupName,
                             long startedAtEpochMs,
                             Long finishedAtEpochMs,
                             int totalPorts,
                             int totalMessages,
                             long totalTasksBudget,
                             long tasksRemaining,
                             boolean anyFailures,
                             boolean anyErrored,
                             List<Failure> failures,
                             double percentCompleteOverall,
                             Map<Integer, PortDetail> perPort,
                             long maxTimeRemainingMs,
                             long timeRemainingMs,
                             Long estimatedFinishedAtEpochMs,
                             Long originallyEstimatedFinishedAtEpochMs,
                             long timeSavedMs,
                             Map<String, Boolean> properties,
                             boolean anyWaitingForResponse)
    {
        this(groupName,
             startedAtEpochMs,
             finishedAtEpochMs,
             totalPorts,
             totalMessages,
             totalTasksBudget,
             tasksRemaining,
             anyFailures,
             anyErrored,
             failures,
             percentCompleteOverall,
             perPort,
             maxTimeRemainingMs,
             timeRemainingMs,
             estimatedFinishedAtEpochMs,
             originallyEstimatedFinishedAtEpochMs,
             timeSavedMs,
             properties,
             anyWaitingForResponse,
             null);
    }
}
