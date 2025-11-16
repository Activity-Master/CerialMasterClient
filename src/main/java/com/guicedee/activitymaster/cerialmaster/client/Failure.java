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
public class Failure implements INgDataType<Failure>
{
    public Integer comPort;
    public String messageId;
    public String title; // title from MessageSpec
    public String friendlyName; // human-friendly message name/title
    public TimedComPortSender.State state;

    public Failure(Integer comPort, String messageId, TimedComPortSender.State state)
    {
        this.comPort = comPort;
        this.messageId = messageId;
        this.state = state;
    }

    public Failure(Integer comPort, String messageId, String friendlyName, TimedComPortSender.State state)
    {
        this.comPort = comPort;
        this.messageId = messageId;
        this.friendlyName = friendlyName;
        this.state = state;
    }

    /**
     * Preferred constructor including MessageSpec title.
     */
    public Failure(Integer comPort, String messageId, String title, String friendlyName, TimedComPortSender.State state)
    {
        this.comPort = comPort;
        this.messageId = messageId;
        this.title = title;
        this.friendlyName = friendlyName;
        this.state = state;
    }
}
