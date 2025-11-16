package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Setter
@NgDataType
@NoArgsConstructor
public class SenderSnapshot implements INgDataType<SenderSnapshot>
{
    public int comPort;
    public int totalPlannedMessages;
    public int messagesCompleted;
    public double percentComplete;
    public long tasksBudget;
    public long tasksRemaining;
    public long worstCaseRemainingMs;
    public long timeRemainingMs; // alias of worstCaseRemainingMs for direct consumption
    public Long groupStartedAtEpochMs;
    public Long groupEndedAtEpochMs;
    public Long estimatedFinishedAtEpochMs; // now + worstCaseRemainingMs (or groupEndedAt when terminal)
    public Long originallyEstimatedFinishedAtEpochMs; // at group start: start + initial worst-case group duration
    public OffsetDateTime groupStartedAt; // UTC
    public OffsetDateTime groupEndedAt; // UTC
    public OffsetDateTime estimatedFinishedAt; // UTC
    public OffsetDateTime originallyEstimatedFinishedAt; // UTC
    public List<MessageStat> waiting; // up to limit
    public MessageStat sending; // 0 or 1
    public List<MessageStat> completed; // last up to limit
    public boolean anyErrored; // roll-up flag: any message in this sender ended in an errored state
    public boolean anyWaitingForResponse; // roll-up: true if a message is currently awaiting response (sending Running/Paused)

    public SenderSnapshot(int comPort,
                          int totalPlannedMessages,
                          int messagesCompleted,
                          double percentComplete,
                          long tasksBudget,
                          long tasksRemaining,
                          long worstCaseRemainingMs,
                          long timeRemainingMs,
                          Long groupStartedAtEpochMs,
                          Long groupEndedAtEpochMs,
                          Long estimatedFinishedAtEpochMs,
                          Long originallyEstimatedFinishedAtEpochMs,
                          List<MessageStat> waiting,
                          MessageStat sending,
                          List<MessageStat> completed,
                          boolean anyErrored,
                          boolean anyWaitingForResponse)
    {
        this.comPort = comPort;
        this.totalPlannedMessages = totalPlannedMessages;
        this.messagesCompleted = messagesCompleted;
        this.percentComplete = percentComplete;
        this.tasksBudget = tasksBudget;
        this.tasksRemaining = tasksRemaining;
        this.worstCaseRemainingMs = worstCaseRemainingMs;
        this.timeRemainingMs = timeRemainingMs;
        this.groupStartedAtEpochMs = groupStartedAtEpochMs;
        this.groupEndedAtEpochMs = groupEndedAtEpochMs;
        this.estimatedFinishedAtEpochMs = estimatedFinishedAtEpochMs;
        this.originallyEstimatedFinishedAtEpochMs = originallyEstimatedFinishedAtEpochMs;
        this.groupStartedAt = TimedComPortSender.toOffset(groupStartedAtEpochMs);
        this.groupEndedAt = TimedComPortSender.toOffset(groupEndedAtEpochMs);
        this.estimatedFinishedAt = TimedComPortSender.toOffset(estimatedFinishedAtEpochMs);
        this.originallyEstimatedFinishedAt = TimedComPortSender.toOffset(originallyEstimatedFinishedAtEpochMs);
        this.waiting = waiting;
        this.sending = sending;
        this.completed = completed;
        this.anyErrored = anyErrored;
        this.anyWaitingForResponse = anyWaitingForResponse;
    }
}
