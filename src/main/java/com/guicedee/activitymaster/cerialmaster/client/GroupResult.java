package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NgDataType
@NoArgsConstructor
public class GroupResult implements INgDataType<GroupResult>
{
  public List<MessageResult> results;

  public GroupResult(List<MessageResult> results)
  {
    this.results = results;
  }
}
