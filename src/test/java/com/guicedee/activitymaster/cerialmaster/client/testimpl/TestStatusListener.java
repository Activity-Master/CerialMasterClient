package com.guicedee.activitymaster.cerialmaster.client.testimpl;

import com.guicedee.activitymaster.cerialmaster.client.ComPortConnection;
import com.guicedee.activitymaster.cerialmaster.client.services.IComPortStatusChanged;
import com.guicedee.cerial.enumerations.ComPortStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestStatusListener implements IComPortStatusChanged<TestStatusListener>
{
  public static record Event(int port, ComPortStatus oldStatus, ComPortStatus newStatus, Instant at) {}

  private static final List<Event> EVENTS = Collections.synchronizedList(new ArrayList<>());

  public static void reset()
  {
    EVENTS.clear();
  }

  public static List<Event> events()
  {
    return new ArrayList<>(EVENTS);
  }

  @Override
  public void onComPortStatusChanged(ComPortConnection<?> comPortConnection, ComPortStatus oldStatus, ComPortStatus newStatus)
  {
    int port = comPortConnection.getComPort();
    EVENTS.add(new Event(port, oldStatus, newStatus, Instant.now()));
  }
}
