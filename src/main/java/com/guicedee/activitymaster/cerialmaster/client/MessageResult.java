package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NgDataType
@NoArgsConstructor
public class MessageResult implements INgDataType<MessageResult>
{
  public String id;
  public String title;
  public String payload;
  public TimedComPortSender.State terminalState;
  public int attempts;
  public Config effectiveConfig;

  public MessageResult(String id, String title, String payload, TimedComPortSender.State terminalState,
                       int attempts, Config effectiveConfig)
  {
    this.id = id;
    this.title = title;
    this.payload = payload;
    this.terminalState = terminalState;
    this.attempts = attempts;
    this.effectiveConfig = effectiveConfig;
  }
}
