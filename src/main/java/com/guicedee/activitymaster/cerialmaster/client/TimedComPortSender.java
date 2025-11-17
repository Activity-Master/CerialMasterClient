package com.guicedee.activitymaster.cerialmaster.client;

import com.guicedee.activitymaster.cerialmaster.client.services.ICerialMasterService;
import com.guicedee.client.IGuiceContext;
import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.MultiEmitter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Timed sender that attempts to send a message to a COM port connection with retry and timeout control.
 * <p>
 * Features:
 * - assignedRetry: number of attempts
 * - assignedDelay: delay between attempts (milliseconds)
 * - assignedTimeout: a final grace period after retries during which an external thread can mark the message as received
 * - pause/resume support
 * - complete(): mark received externally to stop retries and finish immediately
 * - cancel(): stop everything with a terminal error
 * - Reactive status updates exposed via Mutiny Multi
 * - Batch (group) processing: enqueue a FIFO group of messages with per-message config and get a Uni when done
 */
public class TimedComPortSender
{

  /**
   * Convert epoch milliseconds to OffsetDateTime in UTC, or null if ms is null or <= 0.
   */
  static OffsetDateTime toOffset(Long ms)
  {
    if (ms == null)
    {
      return null;
    }
    try
    {
      return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }
    catch (Throwable t)
    {
      return null;
    }
  }

  public enum State
  {Idle, Running, Paused, Completed, TimedOut, Cancelled, Error}


  /**
   * Callback for an attempt to send. Return true/false in a completed future to indicate success/failure of attempt.
   * This is kept generic to avoid direct coupling to underlying serial implementation.
   */
  @FunctionalInterface
  public interface AttemptFn extends BiFunction<ComPortConnection<?>, Integer, CompletionStage<Boolean>>
  {
  }

  private final ComPortConnection<?> connection;
  private final ScheduledExecutorService scheduler;

  // Default config for the sender; used when no per-message config is provided
  private volatile com.guicedee.activitymaster.cerialmaster.client.Config defaultConfig;

  // The config captured for the currently running message
  private volatile com.guicedee.activitymaster.cerialmaster.client.Config activeConfig;

  private volatile AttemptFn attemptFn;
  // Optional hook to transform the per-message Config right before a message starts
  private volatile java.util.function.BiFunction<com.guicedee.activitymaster.cerialmaster.client.MessageSpec, com.guicedee.activitymaster.cerialmaster.client.Config, com.guicedee.activitymaster.cerialmaster.client.Config> beforeStartConfigTransformer;

  private com.guicedee.activitymaster.cerialmaster.client.Config adaptConfig(com.guicedee.activitymaster.cerialmaster.client.Config c)
  {
    if (c == null)
    {
      return null;
    }
    if (c instanceof com.guicedee.activitymaster.cerialmaster.client.Config cc)
    {
      return cc;
    }
    return new Config(c.getAssignedRetry(), c.assignedDelayMs, c.assignedTimeoutMs, c.alwaysWaitFullTimeoutAfterSend);
  }

  private final AtomicInteger attempts = new AtomicInteger(0);
  private final AtomicBoolean paused = new AtomicBoolean(false);
  private final AtomicBoolean completed = new AtomicBoolean(false);
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicBoolean externallyCompleted = new java.util.concurrent.atomic.AtomicBoolean(false);
  // When true, suppress emitting status/progress and scheduling snapshot publishes (used for silent cancellations)
  private final java.util.concurrent.atomic.AtomicBoolean suppressTelemetry = new java.util.concurrent.atomic.AtomicBoolean(false);

  // Guards to ensure a message cannot process more than one reply/terminalization
  // Tracks message ids currently handling a reply (e.g., complete()/error()) to avoid duplicate processing while in-flight
  private final java.util.Set<String> handlingReplyIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
  // Tracks message ids that have already been terminalized to avoid processing a duplicate terminal event for the same message
  private final java.util.Set<String> terminalizedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private final Object lock = new Object();
  private volatile ScheduledFuture<?> currentSchedule;

  // Reactive status streams
  private final Multi<com.guicedee.activitymaster.cerialmaster.client.StatusUpdate> status;
  private MultiEmitter<? super com.guicedee.activitymaster.cerialmaster.client.StatusUpdate> emitter;

  private final Multi<com.guicedee.activitymaster.cerialmaster.client.MessageProgress> messageProgress;
  private MultiEmitter<? super com.guicedee.activitymaster.cerialmaster.client.MessageProgress> messageEmitter;

  // Group processing structures
  private static class GroupRequest
  {
    final java.util.List<com.guicedee.activitymaster.cerialmaster.client.MessageSpec> specs;
    final java.util.concurrent.CompletableFuture<com.guicedee.activitymaster.cerialmaster.client.GroupResult> future;
    final java.util.List<com.guicedee.activitymaster.cerialmaster.client.MessageResult> results = new java.util.ArrayList<>();
    int index = 0;

    GroupRequest(java.util.List<com.guicedee.activitymaster.cerialmaster.client.MessageSpec> specs, java.util.concurrent.CompletableFuture<com.guicedee.activitymaster.cerialmaster.client.GroupResult> future)
    {
      this.specs = specs;
      this.future = future;
    }
  }

  private final Queue<GroupRequest> groupQueue = new ArrayDeque<>();
  private GroupRequest activeGroup;
  private MessageSpec activeMessage;

  // Listener invoked on terminal state for current message
  private volatile java.util.function.Consumer<MessageResult> terminalListener;

  // Per-message completion futures for registration/awaiting status
  private final Map<String, CompletableFuture<MessageResult>> messageFutures = new ConcurrentHashMap<>();
  private CompletableFuture<MessageResult> currentMessageFuture;

  // Priority messages queue (always run next after current message). Processed before groups when idle.
  private final Queue<MessageSpec> priorityQueue = new ArrayDeque<>();

  // ===== Snapshot/statistics tracking =====
  // Attempts observed per message id
  private final Map<String, Integer> attemptsByMessage = new ConcurrentHashMap<>();
  // Start/end timestamps per message (epoch ms)
  private final Map<String, Long> messageStartEpochMs = new ConcurrentHashMap<>();
  private final Map<String, Long> messageEndEpochMs = new ConcurrentHashMap<>();
  // Bounded completed history (last 100)
  private final ArrayDeque<MessageStat> completedHistory = new ArrayDeque<>();
  // Current group timing (epoch ms)
  private volatile Long currentGroupStartEpochMs;
  private volatile Long currentGroupEndEpochMs;
  private volatile Long currentGroupInitialWorstRemainingMs;
  // Track last completed group's planned/complete counts for snapshot after completion
  private volatile Integer lastGroupTotalPlanned;
  private volatile Integer lastGroupCompleted;

  // === Stats consumer support ===
  private final java.util.List<java.util.function.Consumer<SenderSnapshot>> snapshotConsumers = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final java.util.concurrent.ScheduledExecutorService statsScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "TimedComPortSender-Stats-" + System.identityHashCode(this));
    t.setDaemon(true);
    return t;
  });
  private final java.util.Random statsRng = new java.util.Random();
  private volatile java.util.concurrent.ScheduledFuture<?> statsFuture;
  private volatile String lastSnapshotFp;

  /**
   * Register a consumer to receive debounced SenderSnapshot updates. Returns AutoCloseable to unregister.
   */
  public java.lang.AutoCloseable onStatisticsUpdated(java.util.function.Consumer<com.guicedee.activitymaster.cerialmaster.client.SenderSnapshot> consumer)
  {
    if (consumer == null)
    {
      return () -> {
      };
    }
    snapshotConsumers.add(consumer);
    // ensure an update soon for new listeners
    scheduleSnapshotPublish();
    return () -> snapshotConsumers.remove(consumer);
  }

  private String fingerprintSenderSnapshot(com.guicedee.activitymaster.cerialmaster.client.SenderSnapshot s)
  {
    if (s == null)
    {
      return "null";
    }
    String sendingId = (s.getSending() == null || s.getSending().getId() == null) ? "-" : s.getSending().getId();
    String sendingTitle = (s.getSending() == null || s.getSending().getTitle() == null) ? "-" : s.getSending().getTitle();
    int waitingSize = s.waiting == null ? 0 : s.waiting.size();
    int completedSize = s.completed == null ? 0 : s.completed.size();
    return new StringBuilder(128)
               .append(s.comPort)
               .append('|')
               .append(s.totalPlannedMessages)
               .append('|')
               .append(s.messagesCompleted)
               .append('|')
               .append(String.format(java.util.Locale.ROOT, "%.4f", s.percentComplete))
               .append('|')
               .append(s.tasksRemaining)
               .append('|')
               .append(s.timeRemainingMs)
               .append('|')
               .append(s.groupStartedAtEpochMs)
               .append('|')
               .append(s.groupEndedAtEpochMs)
               .append('|')
               .append(s.estimatedFinishedAtEpochMs)
               .append('|')
               .append(waitingSize)
               .append('|')
               .append(completedSize)
               .append('|')
               .append(sendingId)
               .append('|')
               .append(sendingTitle)
               .toString();
  }

  private void scheduleSnapshotPublish()
  {
    // coalesce to 300-700ms delay, always send latest snapshot, suppress duplicates
    synchronized (lock)
    {
      if (statsFuture != null && !statsFuture.isDone())
      {
        return;
      }
      int delay = 300 + statsRng.nextInt(401);
      statsFuture = statsScheduler.schedule(() -> {
        try
        {
          com.guicedee.activitymaster.cerialmaster.client.SenderSnapshot snap = snapshot(25, 100);
          String fp = fingerprintSenderSnapshot(snap);
          String last = lastSnapshotFp;
          if (!java.util.Objects.equals(last, fp))
          {
            for (java.util.function.Consumer<com.guicedee.activitymaster.cerialmaster.client.SenderSnapshot> c : snapshotConsumers)
            {
              try
              {
                c.accept(snap);
              }
              catch (Throwable ignored)
              {
              }
            }
            lastSnapshotFp = fp;
          }
        }
        finally
        {
          synchronized (lock)
          {
            statsFuture = null;
          }
        }
      }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
  }

  public TimedComPortSender(ComPortConnection<?> connection, com.guicedee.activitymaster.cerialmaster.client.Config config)
  {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.defaultConfig = Objects.requireNonNull(config, "config");
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "TimedComPortSender-" + connection.getComPort());
      t.setDaemon(true);
      return t;
    });

    this.status = Multi.createFrom()
                      .emitter((MultiEmitter<? super com.guicedee.activitymaster.cerialmaster.client.StatusUpdate> e) -> this.emitter = e)
                      .onOverflow()
                      .drop()
                      .emitOn(Infrastructure.getDefaultExecutor());
    this.messageProgress = Multi.createFrom()
                               .emitter((MultiEmitter<? super com.guicedee.activitymaster.cerialmaster.client.MessageProgress> e) -> this.messageEmitter = e)
                               .onOverflow()
                               .drop()
                               .emitOn(Infrastructure.getDefaultExecutor());
    emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(0, State.Idle, "Initialized"));
    emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(null, null, null, 0, State.Idle, defaultConfig, defaultConfig, "Initialized"));
    // ensure initial stats update soon
    scheduleSnapshotPublish();
  }

  public Multi<com.guicedee.activitymaster.cerialmaster.client.StatusUpdate> status()
  {
    return status;
  }

  public Multi<com.guicedee.activitymaster.cerialmaster.client.MessageProgress> messageProgress()
  {
    return messageProgress;
  }

  /**
   * Reset the sender status to Idle in preparation for a new run (e.g., manual retry).
   * This does not clear queued work; it only resets per-run flags and emits Idle to subscribers.
   */
  public void resetForRetry(String reason)
  {
    try
    {
      // Reset flags so a new run can start cleanly
      this.completed.set(false);
      this.cancelled.set(false);
      this.externallyCompleted.set(false);
      this.paused.set(false);
      // Cancel any lingering schedules
      cancelCurrentSchedule();
      // Clear active per-message context; new run will set these again
      synchronized (lock)
      {
        this.activeMessage = null;
        this.activeConfig = null;
        this.terminalListener = null;
        this.currentMessageFuture = null;
        // Reset group timing/markers so new snapshots don't reflect prior group end
        this.currentGroupStartEpochMs = null;
        this.currentGroupEndEpochMs = null;
        this.currentGroupInitialWorstRemainingMs = null;
        this.lastGroupTotalPlanned = null;
        this.lastGroupCompleted = null;
      }
      // Reset attempts for a fresh start
      this.attempts.set(0);
      // Clear completed history so per-sender anyErrored and last terminal states reset for new run
      try
      {
        synchronized (completedHistory)
        {
          completedHistory.clear();
        }
      }
      catch (Throwable ignored1)
      {
      }
      // Emit Idle to both status and message progress streams to visually reset UI
      emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(0, State.Idle, reason == null ? "Reset for retry" : reason));
      emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(null, null, null, 0, State.Idle, this.defaultConfig, this.defaultConfig, reason == null ? "Reset for retry" : reason));
    }
    catch (Throwable ignored)
    {
    }
  }

  public TimedComPortSender setAttemptFn(AttemptFn fn)
  {
    this.attemptFn = fn;
    return this;
  }

  /**
   * Set a hook that can transform the per-message Config just before a message starts processing.
   * If the function returns null or throws, the original config is used.
   */
  public TimedComPortSender setBeforeStartConfig(java.util.function.BiFunction<com.guicedee.activitymaster.cerialmaster.client.MessageSpec, com.guicedee.activitymaster.cerialmaster.client.Config, com.guicedee.activitymaster.cerialmaster.client.Config> fn)
  {
    this.beforeStartConfigTransformer = fn;
    return this;
  }

  public com.guicedee.activitymaster.cerialmaster.client.Config getConfig()
  {
    return defaultConfig;
  }

  public void updateConfig(com.guicedee.activitymaster.cerialmaster.client.Config config)
  {
    this.defaultConfig = Objects.requireNonNull(config);
  }

  public boolean isPaused()
  {
    return paused.get();
  }

  public void pause()
  {
    if (paused.compareAndSet(false, true))
    {
      emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(attempts.get(), State.Paused, "Paused"));
      emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Paused, activeConfig, defaultConfig, "Paused"));
    }
  }

  public void resume()
  {
    if (paused.compareAndSet(true, false))
    {
      emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(attempts.get(), State.Running, "Resumed"));
      emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Running, activeConfig, defaultConfig, "Resumed"));
      // If there is an active message, resume its scheduling; otherwise, if idle but work is queued, kick it off now
      synchronized (lock)
      {
        if (activeMessage != null)
        {
          scheduleNext(0);
        }
        else
        {
          // Try priority first, then continue group or move to next group
          if (!processNextPriorityLocked())
          {
            if (activeGroup != null)
            {
              processNextMessageInActiveGroupLocked();
            }
            else if (!groupQueue.isEmpty())
            {
              moveToNextGroupLocked();
            }
            // else: nothing queued; remain idle
          }
        }
      }
    }
  }

  public void complete()
  {
    // Prevent processing multiple replies for the same message id
    String id = activeMessageId();
    if (id != null && !handlingReplyIds.add(id))
    {
      // Already handling/handled a reply for this message id; ignore duplicates
      return;
    }
    Config cfg = this.activeConfig != null ? this.activeConfig : this.defaultConfig;
    boolean alwaysWait = (cfg != null && cfg.alwaysWaitFullTimeoutAfterSend);

    if (alwaysWait)
    {
      // Record external completion and wait for the configured timeout to elapse before finalizing
      if (this.externallyCompleted.compareAndSet(false, true))
      {
        // If completion is triggered before any attempt occurred, perform a best-effort single connect+send
        try
        {
          if (attempts.get() == 0)
          {
            ensureConnectedDirect();
            trySendDirect(activeMessagePayload());
          }
        }
        catch (Throwable ignored)
        {
        }
        // Ensure a timeout schedule exists. If already scheduled (from a successful attempt), do not reset it.
        synchronized (lock)
        {
          if (currentSchedule == null || currentSchedule.isDone())
          {
            waitForTimeoutOrCompletion();
          }
        }
        emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(attempts.get(), State.Running, "Externally completed; waiting for timeout"));
        emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Running, activeConfig, defaultConfig, "Externally completed; waiting for timeout"));
      }
      // Keep handling flag until terminalization callback occurs
      return;
    }

    // Immediate completion behavior when not configured to wait full timeout
    if (completed.compareAndSet(false, true))
    {
      try
      {
        if (attempts.get() == 0)
        {
          ensureConnectedDirect();
          trySendDirect(activeMessagePayload());
        }
      }
      catch (Throwable ignored)
      {
        // ignore: external completion should still succeed
      }
      cancelCurrentSchedule();
      emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(attempts.get(), State.Completed, "Externally completed"));
      emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Completed, activeConfig, defaultConfig, "Externally completed"));
      notifyTerminal(buildCurrentMessageResult(State.Completed));
    }
    else
    {
      // No state change; release the handling flag
      if (id != null)
      {
        handlingReplyIds.remove(id);
      }
    }
  }

  public void cancel(String reason)
  {
    cancel(reason, false);
  }

  /**
   * Cancel processing. When suppressTelemetry is true, no status/progress emissions or snapshot publishes will be performed.
   */
  public void cancel(String reason, boolean suppressTelemetry)
  {
    if (cancelled.compareAndSet(false, true))
    {
      // Set suppression flag for the duration of cancellation flow
      boolean prevSuppressed = this.suppressTelemetry.getAndSet(suppressTelemetry);
      try
      {
        cancelCurrentSchedule();
        if (!this.suppressTelemetry.get())
        {
          emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(attempts.get(), State.Cancelled, reason == null ? "Cancelled" : reason));
          emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Cancelled, activeConfig, defaultConfig, reason == null ? "Cancelled" : reason));
        }
        notifyTerminal(buildCurrentMessageResult(State.Cancelled));
        // After cancelling the current message, proactively finalize the active group so that future groups can run
        synchronized (lock)
        {
          // Mark group end timestamp and finalize counters
          if (activeGroup != null)
          {
            if (lastGroupTotalPlanned == null)
            {
              lastGroupTotalPlanned = activeGroup.specs != null ? activeGroup.specs.size() : 0;
            }
            if (lastGroupCompleted == null)
            {
              lastGroupCompleted = Math.min(activeGroup.results != null ? activeGroup.results.size() : 0, lastGroupTotalPlanned);
            }
            currentGroupEndEpochMs = System.currentTimeMillis();
            // Complete the group's future with results collected so far
            try
            {
              activeGroup.future.complete(new GroupResult(java.util.List.copyOf(activeGroup.results)));
            }
            catch (Throwable ignored)
            {
            }
            activeGroup = null;
          }
          // Clear any active message context
          // Release reply-handling guard for the active message id (if any)
          try { String id = activeMessageId(); if (id != null) { handlingReplyIds.remove(id); } } catch (Throwable ignored) {}
          activeMessage = null;
          currentMessageFuture = null;
          terminalListener = null;
          // Optional: clear any pending priority messages since the run was cancelled
          try { priorityQueue.clear(); } catch (Throwable ignored) {}
        }
        // Keep scheduler alive for reuse across messages
      }
      finally
      {
        // Restore previous suppression state
        this.suppressTelemetry.set(prevSuppressed);
      }
    }
  }

  /**
   * Mark the current active message as errored (terminal). This stops further attempts and emits a terminal Error state.
   */
  public void error(String reason)
  {
    // Prevent processing multiple replies/errors for the same message id
    String id = activeMessageId();
    if (id != null && !handlingReplyIds.add(id))
    {
      return;
    }
    if (completed.compareAndSet(false, true))
    {
      cancelCurrentSchedule();
      String msg = (reason == null || reason.isBlank()) ? "Errored" : reason;
      emit(new com.guicedee.activitymaster.cerialmaster.client.StatusUpdate(attempts.get(), State.Error, msg));
      emitMessageProgress(new com.guicedee.activitymaster.cerialmaster.client.MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Error, activeConfig, defaultConfig, msg));
      notifyTerminal(buildCurrentMessageResult(State.Error));
      // Keep scheduler alive for reuse across messages
    }
    else
    {
      // No state change; release the handling flag
      if (id != null)
      {
        handlingReplyIds.remove(id);
      }
    }
  }

  /**
   * Returns the currently active MessageSpec instance (the original object enqueued) if a message is active; otherwise null.
   */
  public MessageSpec getActiveMessageSpec()
  {
    synchronized (lock)
    {
      return activeMessage;
    }
  }

  /**
   * Returns true if there is an active message or an active group currently in progress.
   */
  public boolean hasActiveGroupOrMessage()
  {
    synchronized (lock)
    {
      return activeMessage != null || activeGroup != null;
    }
  }

  /**
   * Start a send using the sender's default config.
   */
  public void start(String payload)
  {
    start(payload, new com.guicedee.activitymaster.cerialmaster.client.Config(defaultConfig.getAssignedRetry(), defaultConfig.assignedDelayMs, defaultConfig.assignedTimeoutMs, defaultConfig.alwaysWaitFullTimeoutAfterSend));
  }

  /**
   * Start a send using a per-message config if provided; otherwise sender default.
   */
  public void start(String payload, com.guicedee.activitymaster.cerialmaster.client.Config perMessageConfig)
  {
    // Reset per-run flags to allow reuse
    this.completed.set(false);
    this.cancelled.set(false);
    this.externallyCompleted.set(false); // Reset externally completed per message; no carry-over between messages as per contract
    // If this is a direct start (not via MessageSpec), ensure payload is captured for sending/telemetry
    if (this.activeMessage == null)
    {
      this.activeMessage = new com.guicedee.activitymaster.cerialmaster.client.MessageSpec(null, payload, perMessageConfig);
    }
    // Capture config for this message (allowing a before-start transformer to adjust it)
    com.guicedee.activitymaster.cerialmaster.client.Config baseCfg = perMessageConfig != null ? perMessageConfig : this.defaultConfig;
    com.guicedee.activitymaster.cerialmaster.client.Config effectiveCfg = baseCfg;
    java.util.function.BiFunction<com.guicedee.activitymaster.cerialmaster.client.MessageSpec, com.guicedee.activitymaster.cerialmaster.client.Config, com.guicedee.activitymaster.cerialmaster.client.Config> hook = this.beforeStartConfigTransformer;
    if (hook != null)
    {
      try
      {
        com.guicedee.activitymaster.cerialmaster.client.Config transformed = hook.apply(this.activeMessage, baseCfg);
        if (transformed != null)
        {
          effectiveCfg = adaptConfig(transformed);
        }
      }
      catch (Throwable ignored)
      {
      }
    }
    this.activeConfig = effectiveCfg;
    // Also reflect the effective config into the active MessageSpec for telemetry/snapshots
    try {
      if (this.activeMessage != null) {
        this.activeMessage.setConfig(effectiveCfg);
        // Propagate config-level alwaysSucceed to the message flag without overriding an explicit true on the message
        if (effectiveCfg != null && effectiveCfg.alwaysSucceed) {
          this.activeMessage.alwaysSucceed = true;
        }
      }
    } catch (Throwable ignored) {}
    // reset per-run counters/state
    this.attempts.set(0);
    this.paused.set(false);
    cancelCurrentSchedule();
    // Reset dedupe guards for the new active message id
    try { String id = activeMessageId(); if (id != null) { handlingReplyIds.remove(id); terminalizedIds.remove(id); } } catch (Throwable ignored) {}
    emit(new StatusUpdate(attempts.get(), State.Running, "Starting"));
    emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Running, activeConfig, defaultConfig, "Starting"));
    scheduleNext(0);
  }

  private void scheduleNext(long delayMs)
  {
    synchronized (lock)
    {
      if (cancelled.get() || completed.get() || paused.get())
      {
        return;
      }
      cancelCurrentSchedule();
      currentSchedule = scheduler.schedule(this::doAttempt, delayMs, TimeUnit.MILLISECONDS);
    }
  }

  private void doAttempt()
  {
    // Guard against race with cancellation/completion/pause
    if (cancelled.get() || completed.get())
    {
      return;
    }
    if (paused.get())
    { // reschedule when resumed
      return;
    }
    int attemptNum = attempts.incrementAndGet();
    try
    {
      if (attemptFn == null)
      {
        // Default behavior: try to ensure connection is open and send the payload via common methods.
        boolean ok = false;
        // Ensure the message content is generated immediately before sending
        String payload = null;
        MessageSpec am = this.activeMessage;
        if (am != null) {
          am.generateId();
          payload = am.generateMessage(attemptNum);
        } else {
          // fallback to previously captured payload if no active message object
          payload = activeMessagePayload();
        }
        // Re-check after potentially heavy work
        if (cancelled.get() || completed.get() || paused.get())
        {
          return;
        }
        try
        {
          ensureConnectedDirect();
          // One more guard after potentially slow connect
          if (cancelled.get() || completed.get() || paused.get())
          {
            return;
          }
          // After connecting, verify port status to decide next action
          com.guicedee.cerial.enumerations.ComPortStatus status = null;
          try {
            status = getCanonicalConnection().getComPortStatus();
          } catch (Throwable ignored) {}
          if (status != null) {
            // If pausing operations (Opening, FileTransfer, etc.), reschedule shortly without sending
            if (com.guicedee.cerial.enumerations.ComPortStatus.pauseOperations.contains(status)) {
              emit(new StatusUpdate(attemptNum, State.Running, "Port status requires pause: " + status));
              emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attemptNum, State.Running, activeConfig, defaultConfig, "Paused on status: " + status));
              scheduleNext(Math.max(50, (activeConfig != null ? activeConfig.assignedDelayMs : defaultConfig.assignedDelayMs)));
              return;
            }
            // If not active (offline/exception), treat as non-terminal failed attempt and continue retries/timeout
            if (!com.guicedee.cerial.enumerations.ComPortStatus.portActive.contains(status)) {
              if (com.guicedee.cerial.enumerations.ComPortStatus.exceptionOperations.contains(status)
                  || com.guicedee.cerial.enumerations.ComPortStatus.portOffline.contains(status)) {
                emit(new StatusUpdate(attemptNum, State.Running, "Port not active: " + status));
                emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attemptNum, State.Running, activeConfig, defaultConfig, "Port not active: " + status));
                onAttemptFinished(false, attemptNum, null);
                return;
              }
            }
          }
          // Guard right before actual transmit
          if (cancelled.get() || completed.get() || paused.get())
          {
            return;
          }
          ok = trySendDirect(payload);
        }
        catch (Throwable t)
        {
          // reflectively attempting send failed; treat as a non-terminal failed attempt
          emit(new StatusUpdate(attemptNum, State.Running, "Attempt send error: " + t.getMessage()));
          emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attemptNum, State.Running, activeConfig, defaultConfig, "Attempt send error: " + t.getMessage()));
          onAttemptFinished(false, attemptNum, t);
          return;
        }
        emit(new StatusUpdate(attemptNum, State.Running, "Attempt result: " + ok));
        emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attemptNum, State.Running, activeConfig, defaultConfig, "Attempt result: " + ok));
        onAttemptFinished(ok, attemptNum, null);
        return;
      }
      CompletionStage<Boolean> stage = attemptFn.apply(connection, attemptNum);
      stage.whenComplete((ok, ex) -> {
        if (ex != null)
        {
          emit(new StatusUpdate(attemptNum, State.Running, "Attempt error: " + ex.getMessage()));
          emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attemptNum, State.Running, activeConfig, defaultConfig, "Attempt error: " + ex.getMessage()));
          onAttemptFinished(false, attemptNum, ex);
        }
        else
        {
          emit(new StatusUpdate(attemptNum, State.Running, "Attempt result: " + ok));
          emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attemptNum, State.Running, activeConfig, defaultConfig, "Attempt result: " + ok));
          onAttemptFinished(Boolean.TRUE.equals(ok), attemptNum, null);
        }
      });
    }
    catch (Throwable t)
    {
      // Treat unexpected exceptions during an attempt as a non-terminal failed attempt
      emit(new StatusUpdate(attemptNum, State.Running, "Attempt threw: " + t.getMessage()));
      emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attemptNum, State.Running, activeConfig, defaultConfig, "Attempt threw: " + t.getMessage()));
      onAttemptFinished(false, attemptNum, t);
    }
  }

  private void onAttemptFinished(boolean success, int attemptNum, Throwable error)
  {
    if (cancelled.get() || completed.get())
    {
      return;
    }

    Config cfg = this.activeConfig != null ? this.activeConfig : this.defaultConfig;
    boolean forceFullRetries = false;
    com.guicedee.activitymaster.cerialmaster.client.MessageSpec m = this.activeMessage;
    if (m != null && m.alwaysSucceed)
    {
      forceFullRetries = true;
    }

    // Decide next action considering success/failure, external completion, and retry budget
    boolean retriesRemaining = attemptNum <= cfg.getAssignedRetry();
    // Guard against tight retry loops when delay is configured as 0 (e.g., unavailable port scenarios)
    long delay = cfg.assignedDelayMs;
    if (delay < 25)
    {
      delay = 25; // minimal backoff to avoid busy looping
    }

    // If the attempt succeeded but we have not received external completion yet, continue retrying
    // until the retry budget is exhausted; then wait for timeout/completion window.
    if (success && !forceFullRetries && !externallyCompleted.get())
    {
      if (retriesRemaining)
      {
        scheduleNext(delay);
      }
      else
      {
        // Retries exhausted after successful sends — now wait for completion signal or timeout
        waitForTimeoutOrCompletion();
      }
      return;
    }

    // For failures, or when forcing full retries on success, follow the standard retry budget
    if (retriesRemaining)
    {
      scheduleNext(delay);
    }
    else
    {
      // Retries exhausted -> start timeout window
      waitForTimeoutOrCompletion();
    }
  }

  private void waitForTimeoutOrCompletion()
  {
    synchronized (lock)
    {
      cancelCurrentSchedule();
      if (cancelled.get() || completed.get())
      {
        return;
      }
      Config cfg = this.activeConfig != null ? this.activeConfig : this.defaultConfig;
      emit(new StatusUpdate(attempts.get(), State.Running, "Waiting for external completion (timeout=" + cfg.assignedTimeoutMs + "ms)"));
      emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), State.Running, activeConfig, defaultConfig, "Waiting for external completion"));
      currentSchedule = scheduler.schedule(() -> {
        if (completed.get() || cancelled.get())
        {
          return;
        }
        // Determine terminal state on timeout considering alwaysSucceed flag and external completion
        com.guicedee.activitymaster.cerialmaster.client.MessageSpec m = activeMessage;
        boolean forceSuccess = ((m != null && m.alwaysSucceed) || externallyCompleted.get());
        State terminal = forceSuccess ? State.Completed : State.TimedOut;
        String statusMsg = forceSuccess ? "Timeout reached – marking as Completed" : "Timed out waiting for external completion";
        String progressMsg = forceSuccess ? "Timeout reached – success" : "Timed out";
        emit(new StatusUpdate(attempts.get(), terminal, statusMsg));
        emitMessageProgress(new MessageProgress(activeMessageId(), activeMessageTitle(), activeMessagePayload(), attempts.get(), terminal, activeConfig, defaultConfig, progressMsg));
        notifyTerminal(buildCurrentMessageResult(terminal));
        // Keep scheduler alive for reuse across messages
      }, cfg.assignedTimeoutMs, TimeUnit.MILLISECONDS);
    }
  }

  private void cancelCurrentSchedule()
  {
    ScheduledFuture<?> f = currentSchedule;
    if (f != null)
    {
      f.cancel(false);
    }
  }

  private void emit(com.guicedee.activitymaster.cerialmaster.client.StatusUpdate update)
  {
    if (this.suppressTelemetry.get())
    {
      return;
    }
    MultiEmitter<? super com.guicedee.activitymaster.cerialmaster.client.StatusUpdate> e = emitter;
    if (e != null)
    {
      e.emit(update);
    }
    // schedule stats update on status changes
    scheduleSnapshotPublish();
  }

  private void emitMessageProgress(com.guicedee.activitymaster.cerialmaster.client.MessageProgress update)
  {
    // Update local statistics for snapshots
    if (update != null)
    {
      if (update.id != null)
      {
        attemptsByMessage.put(update.id, Math.max(attemptsByMessage.getOrDefault(update.id, 0), Math.max(0, update.attempt)));
      }
      // Detect start of a message
      if (update.note != null && update.note.contains("Starting") && update.id != null)
      {
        messageStartEpochMs.putIfAbsent(update.id, System.currentTimeMillis());
      }
      // Track terminal transitions and maintain completed history (last 100)
      if (update.state == State.Completed || update.state == State.TimedOut || update.state == State.Cancelled || update.state == State.Error)
      {
        Long started = update.id == null ? null : messageStartEpochMs.get(update.id);
        Long ended = update.id == null ? null : System.currentTimeMillis();
        if (update.id != null)
        {
          messageEndEpochMs.put(update.id, ended);
        }
        // compute original estimate using effective config captured on progress
        com.guicedee.activitymaster.cerialmaster.client.Config cfg = update.effectiveConfig != null ? update.effectiveConfig : defaultConfig;
        long worstTotalForMsg = (long) cfg.getAssignedRetry() * cfg.assignedDelayMs + cfg.assignedTimeoutMs;
        Long originalEstimate = started == null ? null : (started + worstTotalForMsg);
        Long estimatedFinish = ended; // terminal -> estimated equals actual finished
        // Push into bounded completed history
        MessageStat stat = new MessageStat(update.id, update.title, update.payload, update.state, update.attempt,
            cfg,
            started,
            ended,
            0L,
            estimatedFinish,
            originalEstimate,
            update.note);
        synchronized (completedHistory)
        {
          completedHistory.addLast(stat);
          while (completedHistory.size() > 100)
          {
            completedHistory.removeFirst();
          }
        }
        // If successfully completed, free per-id references we no longer need
        if (update.state == State.Completed && update.id != null)
        {
          messageFutures.remove(update.id);
          attemptsByMessage.remove(update.id);
          messageStartEpochMs.remove(update.id);
          messageEndEpochMs.remove(update.id);
        }
      }
    }
    if (!this.suppressTelemetry.get())
    {
      MultiEmitter<? super com.guicedee.activitymaster.cerialmaster.client.MessageProgress> e = messageEmitter;
      if (e != null)
      {
        e.emit(update);
      }
      // schedule stats update on message progress
      scheduleSnapshotPublish();
    }
  }

  private String activeMessageId()
  {
    com.guicedee.activitymaster.cerialmaster.client.MessageSpec m = activeMessage;
    return m == null ? null : m.getId();
  }

  private String activeMessagePayload()
  {
    com.guicedee.activitymaster.cerialmaster.client.MessageSpec m = activeMessage;
    return m == null ? null : m.getPayload();
  }

  private String activeMessageTitle()
  {
    com.guicedee.activitymaster.cerialmaster.client.MessageSpec m = activeMessage;
    return m == null ? null : m.getTitle();
  }

  private java.util.concurrent.CompletableFuture<com.guicedee.activitymaster.cerialmaster.client.MessageResult> ensureFutureForId(String id)
  {
    if (id == null)
    {
      return new java.util.concurrent.CompletableFuture<>();
    }
    return messageFutures.computeIfAbsent(id, k -> new CompletableFuture<>());
  }

  private MessageResult buildCurrentMessageResult(State s)
  {
    return new MessageResult(activeMessageId(), activeMessageTitle(), activeMessagePayload(), s, attempts.get(), activeConfig);
  }

  /**
   * Register/obtain a Uni that completes with the terminal result for the given message id.
   */
  public Uni<MessageResult> onMessageResult(String id)
  {
    return Uni.createFrom()
               .completionStage(ensureFutureForId(id));
  }

  /**
   * Start a single message with per-message config and get a Uni for its result.
   */
  public Uni<MessageResult> start(MessageSpec spec)
  {
    Objects.requireNonNull(spec, "spec");
    CompletableFuture<MessageResult> f;
    synchronized (lock)
    {
      f = ensureFutureForId(spec.getId());
      if (activeMessage == null && activeGroup == null)
      {
        // run immediately
        activeMessage = spec;
        terminalListener = (result) -> onMessageTerminal(result);
        currentMessageFuture = f;
        start(spec.getPayload(), adaptConfig(spec.getConfig()));
        return Uni.createFrom()
                   .completionStage(f);
      }
      else
      {
        // enqueue as priority to run next
        priorityQueue.add(spec);
        // if idle (no current schedule), try kick-off
        if (activeMessage == null)
        {
          processNextPriorityLocked();
        }
        return Uni.createFrom()
                   .completionStage(f);
      }
    }
  }

  /**
   * Enqueue a high-priority message to run immediately after the current one.
   */
  public Uni<MessageResult> enqueuePriority(MessageSpec spec)
  {
    Objects.requireNonNull(spec, "spec");
    CompletableFuture<MessageResult> f = ensureFutureForId(spec.getId());
    synchronized (lock)
    {
      priorityQueue.add(spec);
      if (activeMessage == null)
      {
        processNextPriorityLocked();
      }
    }
    return Uni.createFrom()
               .completionStage(f);
  }

  // ===== Group processing API =====

  /**
   * Enqueue a FIFO group for processing and return a Uni completing when the entire group finishes.
   */
  public Uni<GroupResult> enqueueGroup(List<MessageSpec> specs)
  {
    Objects.requireNonNull(specs, "specs");
    // Normalize specs: ensure every message has a non-null id and sensible defaults
    List<MessageSpec> normalized = new ArrayList<>(specs.size());
    for (MessageSpec s : specs)
    {
      if (s == null)
      {
        continue;
      }
      if (s.getId() == null || s.getId().isBlank())
      {
        s.setId(java.util.UUID.randomUUID().toString());
        if (s.getTitle() == null || s.getTitle().isBlank())
        {
          s.setTitle(s.getId());
        }
      }
      if (s.getConfig() == null)
      {
        s.setConfig(this.defaultConfig);
      }
      normalized.add(s);
    }
    CompletableFuture<GroupResult> cf = new CompletableFuture<>();
    GroupRequest req = new GroupRequest(List.copyOf(normalized), cf);
    synchronized (lock)
    {
      groupQueue.add(req);
      // ensure at least one stats update for newly enqueued tasks
      scheduleSnapshotPublish();
      if (activeGroup == null && activeMessage == null)
      {
        // Kick off processing immediately
        moveToNextGroupLocked();
      }
    }
    return Uni.createFrom()
               .completionStage(cf);
  }

  private void moveToNextGroupLocked()
  {
    // Starting a fresh group run after potential cancel: reset per-run flags
    this.cancelled.set(false);
    this.completed.set(false);
    this.attempts.set(0);
    // Ensure paused flag does not prevent the next group from starting (manager-level pauseAll is handled externally)
    this.paused.set(false);

    activeGroup = groupQueue.poll();
    if (activeGroup == null)
    {
      activeMessage = null;
      // If there is no group, maybe a priority message is waiting
      processNextPriorityLocked();
      return;
    }
    // mark group start time
    currentGroupStartEpochMs = System.currentTimeMillis();
    currentGroupEndEpochMs = null;
    // initialize last group counters
    lastGroupTotalPlanned = activeGroup.specs != null ? activeGroup.specs.size() : 0;
    lastGroupCompleted = 0;
    // compute initial worst-case duration for entire group from current index
    long initialWorst = 0L;
    if (activeGroup.specs != null)
    {
      for (MessageSpec spec : activeGroup.specs)
      {
        com.guicedee.activitymaster.cerialmaster.client.Config cfg0 = spec.getConfig() != null ? spec.getConfig() : defaultConfig;
        initialWorst += (long) cfg0.getAssignedRetry() * cfg0.assignedDelayMs + cfg0.assignedTimeoutMs;
      }
    }
    currentGroupInitialWorstRemainingMs = initialWorst;
    processNextMessageInActiveGroupLocked();
  }

  private void processNextMessageInActiveGroupLocked()
  {
    if (cancelled.get())
    {
      return;
    }
    // Priority takes precedence
    if (processNextPriorityLocked())
    {
      return;
    }
    if (activeGroup == null)
    {
      return;
    }
    if (activeGroup.index >= activeGroup.specs.size())
    {
      // complete group
      GroupResult gr = new GroupResult(List.copyOf(activeGroup.results));
      activeGroup.future.complete(gr);
      // mark group end
      currentGroupEndEpochMs = System.currentTimeMillis();
      // retain last group counts (already tracked); ensure consistency
      if (lastGroupTotalPlanned == null)
      {
        lastGroupTotalPlanned = activeGroup.specs != null ? activeGroup.specs.size() : 0;
      }
      if (lastGroupCompleted == null)
      {
        lastGroupCompleted = activeGroup.specs != null ? activeGroup.specs.size() : 0;
      }
      activeGroup = null;
      activeMessage = null;
      // after finishing a group, check priority first, then next group
      if (!processNextPriorityLocked())
      {
        moveToNextGroupLocked();
      }
      return;
    }
    activeMessage = activeGroup.specs.get(activeGroup.index);
    // set a terminal listener for this message
    terminalListener = (result) -> onMessageTerminal(result);
    // ensure a future exists for this message id
    currentMessageFuture = ensureFutureForId(activeMessage.getId());
    // Start the message using its per-message config
    start(activeMessage.getPayload(), adaptConfig(activeMessage.getConfig()));
  }

  private boolean processNextPriorityLocked()
  {
    if (cancelled.get())
    {
      return false;
    }
    if (activeMessage != null)
    {
      return false; // currently running something
    }
    MessageSpec next = priorityQueue.poll();
    if (next == null)
    {
      return false;
    }
    activeMessage = next;
    terminalListener = (result) -> onMessageTerminal(result);
    currentMessageFuture = ensureFutureForId(next.getId());
    start(next.getPayload(), adaptConfig(next.getConfig()));
    return true;
  }

  // ===== Snapshot API =====

  /**
   * Builds a bounded snapshot of the current sender state for diagnostics/monitoring.
   * Waiting list is the next up to 25 messages from the active group (after the current index),
   * sending is the currently active message (if any), and completed is the last 100 terminal messages.
   */
  public SenderSnapshot snapshot(int waitingLimit, int completedLimit)
  {
    int wLimit = Math.max(0, waitingLimit);
    int cLimit = Math.max(0, completedLimit);
    List<MessageStat> waiting = new ArrayList<>();
    MessageStat sending = null;
    int totalPlanned = 0;
    int completedCount = 0;
    long tasksBudget = 0;
    long tasksRemaining = 0;
    long worstRemainingMs = 0;
    int comPort = 0;
    try
    {
      comPort = connection.getComPort();
    }
    catch (Throwable ignored)
    {
    }

    synchronized (lock)
    {
      // Determine the source of the current plan for snapshotting:
      // 1) Active group if running
      // 2) Otherwise, peek at the next queued group (so we don't report 100% with 0 planned while work is queued)
      // 3) Otherwise, fall back to last completed group's totals if available
      List<MessageSpec> planned;
      int idx;
      if (activeGroup != null)
      {
        planned = activeGroup.specs != null ? activeGroup.specs : List.of();
        idx = activeGroup.index;
      }
      else if (!groupQueue.isEmpty())
      {
        GroupRequest next = groupQueue.peek();
        planned = (next != null && next.specs != null) ? next.specs : List.of();
        idx = 0;
      }
      else
      {
        planned = List.of();
        idx = 0;
        // If no active or queued group but we have a recently completed group, use its totals for reporting
        if (currentGroupEndEpochMs != null && lastGroupTotalPlanned != null)
        {
          totalPlanned = lastGroupTotalPlanned;
          completedCount = Math.min(lastGroupCompleted != null ? lastGroupCompleted : 0, totalPlanned);
          // after completion, no waiting or sending; tasks remaining and worstRemaining 0
        }
      }
      totalPlanned = planned.size();
      // Build sending stat
      if (activeMessage != null)
      {
        String id = activeMessage.getId();
        int attemptsUsed = attemptsByMessage.getOrDefault(id, attempts.get());
        Config cfg = activeConfig != null ? activeConfig : defaultConfig;
        long msgBudget = cfg.getAssignedRetry();
        long delayMs = cfg.assignedDelayMs;
        long timeoutMs = cfg.assignedTimeoutMs;
        long remainingRetries = Math.max(0, msgBudget - Math.min(msgBudget, attemptsUsed));
        long msgRemainingMs = (id == null) ? 0L : (remainingRetries * delayMs + timeoutMs);
        Long startedAt = id == null ? null : messageStartEpochMs.get(id);
        long worstTotalForMsg = msgBudget * delayMs + timeoutMs;
        Long originalEstimate = startedAt == null ? null : (startedAt + worstTotalForMsg);
        Long estimatedFinish = System.currentTimeMillis() + msgRemainingMs;
        sending = new MessageStat(id, activeMessage.getTitle(), activeMessage.getPayload(), State.Running, attemptsUsed,
            cfg,
            startedAt,
            id == null ? null : messageEndEpochMs.get(id),
            msgRemainingMs,
            estimatedFinish,
            originalEstimate,
            "sending");
      }
      // Waiting (next up to limit)
      if (planned != null && !planned.isEmpty())
      {
        int startIdx = Math.min(Math.max(0, idx), planned.size());
        int endIdx = Math.min(planned.size(), startIdx + wLimit);
        for (int i = startIdx; i < endIdx; i++)
        {
          MessageSpec spec = planned.get(i);
          String id = spec.getId();
          int attemptsUsed = attemptsByMessage.getOrDefault(id, 0);
          com.guicedee.activitymaster.cerialmaster.client.Config cfgW = spec.getConfig() != null ? spec.getConfig() : defaultConfig;
          long msgBudgetW = cfgW.getAssignedRetry();
          long delayMsW = cfgW.assignedDelayMs;
          long timeoutMsW = cfgW.assignedTimeoutMs;
          long remainingRetriesW = Math.max(0, msgBudgetW - Math.min(msgBudgetW, attemptsUsed));
          long msgRemainingMsW = (id == null) ? 0L : (remainingRetriesW * delayMsW + timeoutMsW);
          Long startedAtW = messageStartEpochMs.get(id);
          long worstTotalW = msgBudgetW * delayMsW + timeoutMsW;
          Long originalEstimateW = startedAtW == null ? null : (startedAtW + worstTotalW);
          Long estimatedFinishW = System.currentTimeMillis() + msgRemainingMsW;
          waiting.add(new MessageStat(id, spec.getTitle(), spec.getPayload(), State.Idle, attemptsUsed,
              cfgW,
              startedAtW, messageEndEpochMs.get(id),
              msgRemainingMsW,
              estimatedFinishW,
              originalEstimateW,
              "waiting"));
        }
      }
      // Compute budgets/remaining/worst-case and completed count for the determined plan
      if (planned != null)
      {
        for (int i = 0; i < planned.size(); i++)
        {
          MessageSpec spec = planned.get(i);
          long msgBudget = (spec.getConfig() != null ? spec.getConfig().getAssignedRetry() : defaultConfig.getAssignedRetry());
          long delayMs = (spec.getConfig() != null ? spec.getConfig().assignedDelayMs : defaultConfig.assignedDelayMs);
          long timeoutMs = (spec.getConfig() != null ? spec.getConfig().assignedTimeoutMs : defaultConfig.assignedTimeoutMs);
          tasksBudget += Math.max(0, msgBudget);
          String id = spec.getId();
          boolean terminal = messageEndEpochMs.containsKey(id);
          // If active group, messages before current index are effectively completed
          boolean beforeIndex = (activeGroup != null) && (i < Math.max(0, activeGroup.index));
          if (terminal || beforeIndex)
          {
            completedCount++;
          }
          else
          {
            long attemptsUsed = attemptsByMessage.getOrDefault(id, 0);
            long remainingRetries = Math.max(0, msgBudget - Math.min(msgBudget, attemptsUsed));
            tasksRemaining += remainingRetries;
            worstRemainingMs += (remainingRetries * delayMs + timeoutMs);
          }
        }
      }
    }

    double percent = totalPlanned == 0 ? 100.0 : (completedCount * 100.0) / totalPlanned;

    // Completed bounded list (copy from history, newest last)
    List<MessageStat> completed;
    synchronized (completedHistory)
    {
      int size = completedHistory.size();
      int from = Math.max(0, size - cLimit);
      completed = new ArrayList<>(completedHistory).subList(from, size);
    }

    Long estimatedGroupFinish = null;
    Long originalEstimatedGroupFinish = null;
    if (currentGroupStartEpochMs != null)
    {
      if (currentGroupEndEpochMs != null)
      {
        estimatedGroupFinish = currentGroupEndEpochMs;
      }
      else
      {
        estimatedGroupFinish = System.currentTimeMillis() + worstRemainingMs;
      }
      if (currentGroupInitialWorstRemainingMs != null)
      {
        originalEstimatedGroupFinish = currentGroupStartEpochMs + currentGroupInitialWorstRemainingMs;
      }
    }

    return new SenderSnapshot(comPort,
        totalPlanned,
        completedCount,
        percent,
        tasksBudget,
        tasksRemaining,
        worstRemainingMs,
        worstRemainingMs,
        currentGroupStartEpochMs,
        currentGroupEndEpochMs,
        estimatedGroupFinish,
        originalEstimatedGroupFinish,
        waiting,
        sending,
        completed,
        computeAnyErrored(waiting, sending, completed),
        computeAnyWaitingForResponse(sending));
  }

  private boolean computeAnyWaitingForResponse(MessageStat sending)
  {
    if (sending == null)
    {
      return false;
    }
    return sending.getState() == State.Running || sending.getState() == State.Paused;
  }

  private boolean computeAnyErrored(List<MessageStat> waiting, MessageStat sending, List<MessageStat> completed)
  {
    if (completed != null)
    {
      for (MessageStat ms : completed)
      {
        if (ms != null && ms.isErrored())
        {
          return true;
        }
      }
    }
    synchronized (lock)
    {
      if (activeGroup != null && activeGroup.results != null)
      {
        for (MessageResult r : activeGroup.results)
        {
          if (r == null || r.terminalState == null)
          {
            continue;
          }
          if (r.terminalState == State.TimedOut || r.terminalState == State.Cancelled || r.terminalState == State.Error)
          {
            return true;
          }
        }
      }
    }
    return false;
  }

  private void onMessageTerminal(MessageResult result)
  {
    synchronized (lock)
    {
      // Deduplicate terminalization per message id to ensure only the first terminal event is processed
      String rid = (result == null) ? null : result.id;
      if (rid != null)
      {
        if (!terminalizedIds.add(rid))
        {
          // Already terminalized; ensure handling flag is cleared and ignore duplicates
          handlingReplyIds.remove(rid);
          return;
        }
      }
      // complete per-message future
      if (result != null && result.id != null)
      {
        CompletableFuture<MessageResult> f = messageFutures.get(result.id);
        if (f != null && !f.isDone())
        {
          f.complete(result);
        }
        if (currentMessageFuture != null && !currentMessageFuture.isDone())
        {
          currentMessageFuture.complete(result);
        }
      }
      // add to active group only if this terminal result corresponds to the group's current expected message
      if (activeGroup != null && result != null)
      {
        // Determine the expected id for the current group position (if any)
        String expectedId = null;
        if (activeGroup.specs != null && activeGroup.index < activeGroup.specs.size())
        {
          MessageSpec expected = activeGroup.specs.get(activeGroup.index);
          if (expected != null)
          {
            expectedId = expected.getId();
          }
        }
        // Compute an effective id for comparison without mutating the provided result
        String effectiveId = (result.id == null || result.id.isBlank()) ? null : result.id;
        // Only advance the group if this terminal result matches the expected message for the group
        if (expectedId != null && java.util.Objects.equals(expectedId, effectiveId))
        {
          activeGroup.results.add(result);
          activeGroup.index++;
          if (lastGroupCompleted != null)
          {
            lastGroupCompleted = Math.min(lastGroupTotalPlanned != null ? lastGroupTotalPlanned : Integer.MAX_VALUE,
                (lastGroupCompleted == null ? 0 : lastGroupCompleted) + 1);
          }
        }
      }
      // also record into completed history if not already via emit hook (defensive)
      if (result != null)
      {
        Long start = result.id == null ? null : messageStartEpochMs.get(result.id);
        Long end = result.id == null ? null : messageEndEpochMs.get(result.id);
        com.guicedee.activitymaster.cerialmaster.client.Config cfg = result.effectiveConfig != null ? result.effectiveConfig : defaultConfig;
        long worstTotalForMsg = (long) cfg.getAssignedRetry() * cfg.assignedDelayMs + cfg.assignedTimeoutMs;
        Long originalEstimate = start == null ? null : (start + worstTotalForMsg);
        Long estimatedFinish = end; // terminal state
        MessageStat stat = new MessageStat(result.id, result.title, result.payload, result.terminalState, result.attempts,
            cfg, start, end, 0L, estimatedFinish, originalEstimate, "terminal");
        synchronized (completedHistory)
        {
          completedHistory.addLast(stat);
          while (completedHistory.size() > 100)
          {
            completedHistory.removeFirst();
          }
        }
      }
      // If success, free message/id references we no longer need (keep failures for potential retry)
      if (result != null && result.terminalState == State.Completed && result.id != null)
      {
        messageFutures.remove(result.id);
        attemptsByMessage.remove(result.id);
        messageStartEpochMs.remove(result.id);
        messageEndEpochMs.remove(result.id);
      }
      // clear current
      // Clear handling flag now that terminalization has been processed
      try { if (rid != null) { handlingReplyIds.remove(rid); } } catch (Throwable ignored) {}
      activeMessage = null;
      terminalListener = null;
      currentMessageFuture = null;

      // If the sender was cancelled, do not advance to any further messages. Flush queues as Cancelled.
      if (cancelled.get())
      {
        // Flush priority queue
        while (!priorityQueue.isEmpty())
        {
          MessageSpec next = priorityQueue.poll();
          if (next == null)
          {
            break;
          }
          int att = attemptsByMessage.getOrDefault(next.getId(), 0);
          Config cfg = next.getConfig() != null ? next.getConfig() : defaultConfig;
          MessageResult mr = new MessageResult(next.getId(), next.getTitle(), next.getPayload(), State.Cancelled, att, cfg);
          // complete future for this message
          CompletableFuture<MessageResult> f = messageFutures.get(next.getId());
          if (f == null)
          {
            f = ensureFutureForId(next.getId());
          }
          if (f != null && !f.isDone())
          {
            f.complete(mr);
          }
          // emit progress so history reflects cancellation
          emitMessageProgress(new MessageProgress(mr.id, mr.title, mr.payload, mr.attempts, State.Cancelled, cfg, defaultConfig, "Cancelled"));
        }
        // Flush remaining messages in active group
        if (activeGroup != null && activeGroup.specs != null)
        {
          while (activeGroup.index < activeGroup.specs.size())
          {
            MessageSpec spec = activeGroup.specs.get(activeGroup.index++);
            int att = attemptsByMessage.getOrDefault(spec.getId(), 0);
            Config cfg = spec.getConfig() != null ? spec.getConfig() : defaultConfig;
            MessageResult mr = new MessageResult(spec.getId(), spec.getTitle(), spec.getPayload(), State.Cancelled, att, cfg);
            activeGroup.results.add(mr);
            CompletableFuture<MessageResult> f2 = messageFutures.get(spec.getId());
            if (f2 == null)
            {
              f2 = ensureFutureForId(spec.getId());
            }
            if (f2 != null && !f2.isDone())
            {
              f2.complete(mr);
            }
            emitMessageProgress(new MessageProgress(mr.id, mr.title, mr.payload, mr.attempts, State.Cancelled, cfg, defaultConfig, "Cancelled"));
          }
          // complete the group future and mark end
          if (activeGroup.future != null && !activeGroup.future.isDone())
          {
            activeGroup.future.complete(new GroupResult(List.copyOf(activeGroup.results)));
          }
          currentGroupEndEpochMs = System.currentTimeMillis();
          lastGroupTotalPlanned = activeGroup.specs != null ? activeGroup.specs.size() : 0;
          lastGroupCompleted = lastGroupTotalPlanned;
          activeGroup = null;
        }
        return;
      }

      // Priority messages always go next
      if (!processNextPriorityLocked())
      {
        // continue with current group if any
        processNextMessageInActiveGroupLocked();
      }
    }
  }

  private void notifyTerminal(MessageResult r)
  {
    java.util.function.Consumer<MessageResult> l = terminalListener;
    if (l != null)
    {
      try
      {
        l.accept(r);
      }
      catch (Throwable ignored)
      {
      }
    }
    // also ensure future completion when called directly — only by message id to avoid race with currentMessageFuture
    if (r != null && r.id != null)
    {
      CompletableFuture<MessageResult> f = messageFutures.get(r.id);
      if (f != null && !f.isDone())
      {
        f.complete(r);
      }
    }
  }

  // ==== Default helpers using concrete API: connect() and getConnectionPort().writeBytes(...) ====
  private ComPortConnection<?> getCanonicalConnection()
  {
    try
    {
      Integer port = connection.getComPort();
      if (port != null)
      {
        ComPortConnection<?> fromRegistry = ComPortConnection.PORT_CONNECTIONS.get(port);
        if (fromRegistry != null)
        {
          return fromRegistry;
        }
      }
    }
    catch (Throwable ignored)
    {
    }
    return connection;
  }

  private void ensureConnectedDirect() throws Exception
  {
    // If cancelled, do not perform any connection operations
    if (cancelled.get())
    {
      return;
    }
    // Prefer the direct API on the canonical connection as per contract
    ComPortConnection<?> c = getCanonicalConnection();
    try
    {
      // Re-check after potential lookup
      if (cancelled.get())
      {
        return;
      }
      c.connect();
    }
    catch (Throwable t)
    {
      if (t instanceof Exception e)
      {
        throw e;
      }
      throw new RuntimeException(t);
    }
  }

  private boolean trySendDirect(String payload) throws Exception
  {
    // If payload is null or blank after trimming, do not send any data.
    // Treat as a non-successful attempt so that retries continue until timeout.
    if (payload == null || payload.trim().isEmpty())
    {
      return false;
    }
    // If cancelled, abort immediately without writing
    if (cancelled.get())
    {
      return false;
    }
    ComPortConnection<?> c = getCanonicalConnection();
    // Directly use write(String, boolean) if available as per API contract
    try
    {
      if (cancelled.get())
      {
        return false;
      }
      c.write(payload, true);
      return true;
    }
    catch (NoSuchMethodError | UnsupportedOperationException e)
    {
      // Fall back to write(String)
      try
      {
        if (cancelled.get())
        {
          return false;
        }
        c.write(payload);
        return true;
      }
      catch (Throwable t)
      {
        return false;
      }
    }
  }

  /**
   * Close this sender and its resources. Cancels any scheduled tasks, shuts down internal schedulers,
   * and attempts to disconnect the underlying COM port connection.
   */
  public void close()
  {
    try
    {
      // Move to cancelled state for any active processing
      cancel("Sender closed");
    }
    catch (Throwable ignored)
    {
    }
    try
    {
      cancelCurrentSchedule();
    }
    catch (Throwable ignored)
    {
    }
    // Stop pending stats publication if any
    try
    {
      synchronized (lock)
      {
        if (statsFuture != null)
        {
          statsFuture.cancel(false);
          statsFuture = null;
        }
      }
    }
    catch (Throwable ignored)
    {
    }
    // Shutdown executors
    try
    {
      scheduler.shutdownNow();
    }
    catch (Throwable ignored)
    {
    }
    try
    {
      statsScheduler.shutdownNow();
    }
    catch (Throwable ignored)
    {
    }
    // Best-effort disconnect of the canonical connection
    try
    {
      ComPortConnection<?> c = null;
      try
      {
        c = getCanonicalConnection();
      }
      catch (Throwable ignored)
      {
      }
      if (c != null)
      {
        try
        {
          c.disconnect();
        }
        catch (Throwable ignored)
        {
        }
      }
    }
    catch (Throwable ignored)
    {
    }
  }

}
