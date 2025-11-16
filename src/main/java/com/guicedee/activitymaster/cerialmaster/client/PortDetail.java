package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@NgDataType
public class PortDetail implements INgDataType<PortDetail>
{
    public int comPort;
    public int messages;
    public int messagesCompleted;
    public double percentComplete;
    public long tasksBudget;
    public long tasksRemaining;
    public long worstCaseRemainingMs;
    public long timeRemainingMs; // alias of worstCaseRemainingMs for direct consumption
    public boolean anyErrored;
    public boolean anyWaitingForResponse;

    public PortDetail(int comPort, int messages, int messagesCompleted, double percentComplete, long tasksBudget, long tasksRemaining, long worstCaseRemainingMs, long timeRemainingMs, boolean anyErrored, boolean anyWaitingForResponse)
    {
        this.comPort = comPort;
        this.messages = messages;
        this.messagesCompleted = messagesCompleted;
        this.percentComplete = percentComplete;
        this.tasksBudget = tasksBudget;
        this.tasksRemaining = tasksRemaining;
        this.worstCaseRemainingMs = worstCaseRemainingMs;
        this.timeRemainingMs = timeRemainingMs;
        this.anyErrored = anyErrored;
        this.anyWaitingForResponse = anyWaitingForResponse;
    }
}
