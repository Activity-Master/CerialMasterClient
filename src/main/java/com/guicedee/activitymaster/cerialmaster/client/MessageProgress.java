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
public class MessageProgress implements INgDataType<MessageProgress>
{
  public String id;
  public String title;
  public String payload;
  public int attempt;
  public TimedComPortSender.State state;
  public Config effectiveConfig; // per-message active config
  public Config defaultConfig;   // sender default config (for comparison)
  public String note;

  public MessageProgress(String id, String title, String payload, int attempt, TimedComPortSender.State state,
                         Config effectiveConfig, Config defaultConfig, String note)
  {
    this.id = id;
    this.title = title;
    this.payload = payload;
    this.attempt = attempt;
    this.state = state;
    this.effectiveConfig = effectiveConfig;
    this.defaultConfig = defaultConfig;
    this.note = note;
  }
}
