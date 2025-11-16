package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@NgDataType
@NoArgsConstructor
@Getter
@Setter
public class MessageStat implements INgDataType<MessageStat>
{
    private String id;
    private String title;
    private String payload;
    private TimedComPortSender.State state;
    private int attempts;
    private Config effectiveConfig;
    private Long startedAtEpochMs;
    private Long finishedAtEpochMs; // actual terminal time when known
    private long timeRemainingMs; // worst-case remaining from now for this message; 0 if terminal
    private Long estimatedFinishedAtEpochMs; // now + timeRemainingMs (or finishedAt when terminal)
    private Long originallyEstimatedFinishedAtEpochMs; // at start: startedAt + worst-case for this message
    private OffsetDateTime startedAt; // UTC
    private OffsetDateTime finishedAt; // UTC
    private OffsetDateTime estimatedFinishedAt; // UTC
    private OffsetDateTime originallyEstimatedFinishedAt; // UTC
    private String note;
    private boolean completedSuccessfully;
    private boolean errored;
    private boolean waitingForResponse;

    public MessageStat(String id, String title, String payload, TimedComPortSender.State state, int attempts,
                       Config effectiveConfig, Long startedAtEpochMs, Long finishedAtEpochMs, long timeRemainingMs,
                       Long estimatedFinishedAtEpochMs, Long originallyEstimatedFinishedAtEpochMs, String note)
    {
        this.id = id;
        this.title = title;
        this.payload = payload;
        this.state = state;
        this.attempts = attempts;
        this.effectiveConfig = effectiveConfig;
        this.startedAtEpochMs = startedAtEpochMs;
        this.finishedAtEpochMs = finishedAtEpochMs;
        this.timeRemainingMs = timeRemainingMs;
        this.estimatedFinishedAtEpochMs = estimatedFinishedAtEpochMs;
        this.originallyEstimatedFinishedAtEpochMs = originallyEstimatedFinishedAtEpochMs;
        this.startedAt = TimedComPortSender.toOffset(startedAtEpochMs);
        this.finishedAt = TimedComPortSender.toOffset(finishedAtEpochMs);
        this.estimatedFinishedAt = TimedComPortSender.toOffset(estimatedFinishedAtEpochMs);
        this.originallyEstimatedFinishedAt = TimedComPortSender.toOffset(originallyEstimatedFinishedAtEpochMs);
        this.note = note;
        this.completedSuccessfully = (state == TimedComPortSender.State.Completed);
        this.errored = (state == TimedComPortSender.State.TimedOut
                || state == TimedComPortSender.State.Cancelled
                || state == TimedComPortSender.State.Error);
        this.waitingForResponse = (state == TimedComPortSender.State.Running || state == TimedComPortSender.State.Paused);
    }
}
