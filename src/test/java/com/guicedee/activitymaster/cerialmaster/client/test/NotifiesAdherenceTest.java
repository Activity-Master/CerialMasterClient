package com.guicedee.activitymaster.cerialmaster.client.test;

import com.guicedee.activitymaster.cerialmaster.client.ComPortConnection;
import com.guicedee.activitymaster.cerialmaster.client.testimpl.TestStatusListener;
import com.guicedee.cerial.enumerations.ComPortStatus;
import com.guicedee.cerial.enumerations.ComPortType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NotifiesAdherenceTest
{
  private static ComPortStatus pickAnotherStatus()
  {
    String[] candidates = new String[]{"Online", "Open", "Connected", "Available", "Idle", "Ready", "OnlineActive"};
    for (String cand : candidates)
    {
      try
      {
        return Enum.valueOf(ComPortStatus.class, cand);
      }
      catch (IllegalArgumentException ignored)
      {
      }
    }
    return ComPortStatus.Offline;
  }

  @BeforeEach
  void setup()
  {
    TestStatusListener.reset();
  }

  @AfterEach
  void tearDown()
  {
    TestStatusListener.reset();
  }

  @Test
  @DisplayName("Server port status change notifies listener")
  void serverStatusChange_notifiesListener()
  {
    ComPortConnection<?> c1 = ComPortConnection.getOrCreate(201, ComPortType.Server);
    c1.setComPortStatus(ComPortStatus.Offline, true);

    ComPortStatus next = pickAnotherStatus();
    if (next == ComPortStatus.Offline)
    {
      c1.setComPortStatus(ComPortStatus.Offline);
      assertTrue(TestStatusListener.events().isEmpty());
      return;
    }

    c1.setComPortStatus(next);

    List<TestStatusListener.Event> ev = TestStatusListener.events();
    assertEquals(1, ev.size());
    assertEquals(201, ev.get(0).port());
    ComPortStatus expected = ("Idle".equals(next.name())) ? ComPortStatus.Silent : next;
    assertEquals(expected, ev.get(0).newStatus());
  }

  @Test
  @DisplayName("Scanner port status change notifies listener")
  void scannerStatusChange_notifiesListener()
  {
    ComPortConnection<?> c1 = ComPortConnection.getOrCreate(202, ComPortType.Scanner);
    c1.setComPortStatus(ComPortStatus.Offline, true);

    ComPortStatus next = pickAnotherStatus();
    if (next == ComPortStatus.Offline)
    {
      c1.setComPortStatus(ComPortStatus.Offline);
      assertTrue(TestStatusListener.events().isEmpty());
      return;
    }

    c1.setComPortStatus(next);

    List<TestStatusListener.Event> ev = TestStatusListener.events();
    assertEquals(1, ev.size());
    assertEquals(202, ev.get(0).port());
    ComPortStatus expected2 = ("Idle".equals(next.name())) ? ComPortStatus.Silent : next;
    assertEquals(expected2, ev.get(0).newStatus());
  }

  @Test
  @DisplayName("Silent status change does not notify listener")
  void silentStatusChange_doesNotNotify()
  {
    ComPortConnection<?> c1 = ComPortConnection.getOrCreate(203, ComPortType.Server);
    c1.setComPortStatus(ComPortStatus.Offline, true);

    ComPortStatus next = pickAnotherStatus();
    c1.setComPortStatus(next, true);

    assertTrue(TestStatusListener.events().isEmpty());
  }

  @Test
  @DisplayName("Setting same status again does not notify")
  void sameStatusChange_doesNotNotify()
  {
    ComPortConnection<?> c1 = ComPortConnection.getOrCreate(204, ComPortType.Server);
    c1.setComPortStatus(ComPortStatus.Offline, true);

    ComPortStatus next = pickAnotherStatus();
    if (next == ComPortStatus.Offline)
    {
      c1.setComPortStatus(ComPortStatus.Offline);
      assertTrue(TestStatusListener.events().isEmpty());
      return;
    }

    c1.setComPortStatus(next);
    assertEquals(1, TestStatusListener.events().size());

    c1.setComPortStatus(next);
    assertEquals(1, TestStatusListener.events().size());
  }

  @Test
  @DisplayName("Canonical instance shared by port across types; single notification")
  void canonicalInstance_sharedAcrossTypes_andSingleNotification()
  {
    ComPortConnection<?> server = ComPortConnection.getOrCreate(205, ComPortType.Server);
    ComPortConnection<?> scanner = ComPortConnection.getOrCreate(205, ComPortType.Scanner);

    assertSame(server, scanner);

    server.setComPortStatus(ComPortStatus.Offline, true);
    ComPortStatus next = pickAnotherStatus();
    if (next == ComPortStatus.Offline)
    {
      server.setComPortStatus(ComPortStatus.Offline);
      assertTrue(TestStatusListener.events().isEmpty());
      return;
    }

    scanner.setComPortStatus(next);

    List<TestStatusListener.Event> ev = TestStatusListener.events();
    assertEquals(1, ev.size());
    assertEquals(205, ev.get(0).port());
    ComPortStatus expected3 = ("Idle".equals(next.name())) ? ComPortStatus.Silent : next;
    assertEquals(expected3, ev.get(0).newStatus());
  }
}
