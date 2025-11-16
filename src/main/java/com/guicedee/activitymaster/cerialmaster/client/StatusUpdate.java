package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
@NgDataType
public class StatusUpdate implements INgDataType<StatusUpdate>
{
  public int attempt;
  public TimedComPortSender.State state;
  public String message;

  public StatusUpdate(int attempt, TimedComPortSender.State state, String message)
  {
    this.attempt = attempt;
    this.state = state;
    this.message = message;
  }
}
