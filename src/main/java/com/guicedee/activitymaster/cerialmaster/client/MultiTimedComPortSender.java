package com.guicedee.activitymaster.cerialmaster.client;

import com.google.inject.ConfigurationException;
import com.google.inject.Key;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.VertxEventPublisher;
import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.MultiEmitter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

// Vert.x event publisher for external status updates (reflected at runtime to avoid hard dependency)

/**
 * Manager that coordinates one or more TimedComPortSender instances by COM port.
 * <p>
 * Features:
 * - Register messages per COM port (list) and enqueue them as a FIFO group per-port.
 * - Aggregate status and message-progress streams across all managed senders, tagged by port.
 * - Group-level controls: start (implicit via enqueue), pauseAll, resumeAll, cancelAll which
 * propagate to all currently active senders. These actions also emit manager-level events and
 * the underlying senders/messages will emit their own events (cascade).
 * - Group-level Uni for multi-port enqueues that completes when all per-port groups finish.
 */
public class MultiTimedComPortSender
{
  private static final Logger log = LoggerFactory.getLogger(MultiTimedComPortSender.class);

  // ===== Static configurable publishing addresses =====
  // Vert.x address for aggregate manager updates
  private static volatile String aggregatePublishAddress = "server-task-updates";
  // Pattern for per-sender updates; must include '%d' for 1-based sender index
  private static volatile String senderPublishPattern = "sender-%d-tasks";
  // Global flag to enable/disable Vert.x publishing (onStatisticsUpdated always fires regardless)
  private static volatile boolean publishingEnabled = true;

  /**
   * Returns the current aggregate publish address
   */
  public static String getAggregatePublishAddress()
  {
    return aggregatePublishAddress;
  }

  /**
   * Updates the aggregate publish address (non-null, non-blank)
   */
  public static void setAggregatePublishAddress(String address)
  {
    if (address == null || address.isBlank())
    {
      return;
    }
    aggregatePublishAddress = address;
  }

  /**
   * Returns the current per-sender publish pattern
   */
  public static String getSenderPublishPattern()
  {
    return senderPublishPattern;
  }

  /**
   * Updates the per-sender publish address pattern. Must contain "%d" placeholder for index.
   */
  public static void setSenderPublishPattern(String pattern)
  {
    if (pattern == null || pattern.isBlank())
    {
      return;
    }
    if (!pattern.contains("%d"))
    {
      // enforce presence of index placeholder to avoid collisions
      throw new IllegalArgumentException("senderPublishPattern must contain '%d' placeholder for sender index");
    }
    senderPublishPattern = pattern;
  }

  /**
   * Returns whether Vert.x publishing is enabled
   */
  public static boolean isPublishingEnabled()
  {
    return publishingEnabled;
  }

  /**
   * Enable or disable Vert.x publishing for aggregate and per-sender topics
   */
  public static void setPublishingEnabled(boolean enabled)
  {
    publishingEnabled = enabled;
  }

  /**
   * Convert epoch milliseconds to OffsetDateTime in UTC, or null if ms is null.
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

  // Singleton instance (backward-compatible: constructor remains public for now)
  public static final MultiTimedComPortSender INSTANCE;

  static
  {
    INSTANCE = IGuiceContext.get(MultiTimedComPortSender.class);
  }

  public static MultiTimedComPortSender getInstance()
  {
    return INSTANCE;
  }

  // ===== Run naming and aggregate tracking =====
  private volatile String groupName;
  // Track last run finish time for idle detection
  private volatile long lastRunFinishedAtEpochMs = 0L;
  // Configurable idle timeout (defaults to 2 minutes)
  private volatile Long configuredIdleAfterMs = null;

  public MultiTimedComPortSender setGroupName(String name)
  {
    this.groupName = name;
    log.trace("📚 Setting CerialMaster manager group name: '{}'", name);
    emitStatus(null, TimedComPortSender.State.Idle, "Manager group set: " + name);
    scheduleAggregatePublish();
    return this;
  }

  public String getGroupName()
  {
    return groupName;
  }

  /**
   * Register a hook to transform the per-message Config right before each message starts.
   * The function receives the MessageSpec and the current effective Config (per-message if set, else the sender default)
   * and should return either the same config or a new one. If it returns null or throws, the original config is used.
   */
  public MultiTimedComPortSender setBeforeStartConfig(java.util.function.BiFunction<com.guicedee.activitymaster.cerialmaster.client.MessageSpec, com.guicedee.activitymaster.cerialmaster.client.Config, com.guicedee.activitymaster.cerialmaster.client.Config> fn)
  {
    this.beforeStartConfig = fn;
    // propagate to existing senders
    for (TimedComPortSender s : senders.values())
    {
      try { s.setBeforeStartConfig(fn); } catch (Throwable ignored) {}
    }
    return this;
  }

  public java.util.function.BiFunction<com.guicedee.activitymaster.cerialmaster.client.MessageSpec, com.guicedee.activitymaster.cerialmaster.client.Config, com.guicedee.activitymaster.cerialmaster.client.Config> getBeforeStartConfig()
  {
    return beforeStartConfig;
  }

  private long idleAfterMs()
  {
    // Try obtain from Guice context if available, else default to 120_000 ms
    Long current = configuredIdleAfterMs;
    if (current != null)
    {
      return current;
    }
    try
    {
      Key<Long> key = Key.get(Long.class, Names.named("cerialmaster.manager.idleAfterMs"));
      Long val = IGuiceContext.get(key);
      if (val != null && val > 0)
      {
        configuredIdleAfterMs = val;
        return val;
      }
    }
    catch (Throwable ignored)
    {
    }
    // Fallback: also try integer minutes property (minutes to ms)
    try
    {
      Key<Integer> keyMin = Key.get(Integer.class, Names.named("cerialmaster.manager.idleAfterMinutes"));
      Integer minutes = IGuiceContext.get(keyMin);
      if (minutes != null && minutes > 0)
      {
        long ms = minutes.longValue() * 60_000L;
        configuredIdleAfterMs = ms;
        return ms;
      }
    }
    catch (Throwable ignored)
    {
    }
    configuredIdleAfterMs = 120_000L;
    return 120_000L;
  }


  // Tracking for the current run
  private final Object aggLock = new Object();
  private volatile boolean runActive = false;
  private volatile long runStartNanos = 0L;
  private volatile long runStartEpochMs = 0L;
  private volatile CompletableFuture<AggregateProgress> aggFuture;

  // Static planning extracted at enqueue time
  private volatile Map<Integer, List<MessageSpec>> plannedByPort;
  private volatile Config plannedBaseConfig;
  private volatile long runInitialWorstRemainingMs;
  // Per-run custom properties map
  private volatile Map<String, Boolean> plannedGroupProperties;

  // Live stats
  private final Map<String, Integer> attemptsByMessage = new ConcurrentHashMap<>();
  private final Set<String> terminalMessages = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> messageStartNanos = new ConcurrentHashMap<>();
  private final Map<String, Long> messageEndNanos = new ConcurrentHashMap<>();
  private final List<com.guicedee.activitymaster.cerialmaster.client.Failure> failures = new CopyOnWriteArrayList<>();
  // Map of active/known message id to its COM port (best-effort, updated on start and cleared on terminal)
  private final Map<String, Integer> messageIdToPort = new ConcurrentHashMap<>();

  // ===== Public manager-level event types =====

  public static final class ManagerStatus
  {
    public final Integer comPort; // may be null for pure manager-level messages
    public final TimedComPortSender.State state;
    public final String message;

    public ManagerStatus(Integer comPort, TimedComPortSender.State state, String message)
    {
      this.comPort = comPort;
      this.state = state;
      this.message = message;
    }
  }

  public static final class ManagerMessageProgress
  {
    public final Integer comPort;
    public final MessageProgress progress;

    public ManagerMessageProgress(Integer comPort, MessageProgress progress)
    {
      this.comPort = comPort;
      this.progress = progress;
    }
  }

  private final Map<Integer, TimedComPortSender> senders = new ConcurrentHashMap<>();
  // Optional hook to transform the per-message Config right before a message starts on any sender
  private volatile java.util.function.BiFunction<com.guicedee.activitymaster.cerialmaster.client.MessageSpec, com.guicedee.activitymaster.cerialmaster.client.Config, com.guicedee.activitymaster.cerialmaster.client.Config> beforeStartConfig;
  // Maintain deterministic insertion order for index-based publishing (1-based positions)
  private final CopyOnWriteArrayList<Integer> orderedPorts = new CopyOnWriteArrayList<>();

  private final Multi<ManagerStatus> status;
  private final Multi<ManagerStatus> rawStatus;
  private final java.util.Set<MultiEmitter<? super ManagerStatus>> statusEmitters = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

  private final Multi<ManagerMessageProgress> progress;
  private final Multi<ManagerMessageProgress> rawProgress;
  private final java.util.Set<MultiEmitter<? super ManagerMessageProgress>> progressEmitters = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

  private final AtomicBoolean pausedAll = new AtomicBoolean(false);

  // Debounced event publishing infrastructure (per-address)
  private final java.util.concurrent.ScheduledExecutorService eventScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "MultiTimedComPortSender-Events");
    t.setDaemon(true);
    return t;
  });

  private static final class DebounceEntry
  {
    java.util.function.Supplier<Object> payloadSupplier;
    java.util.function.Supplier<String> fingerprintSupplier;
    java.util.concurrent.ScheduledFuture<?> future;
    long scheduledSeq; // monotonically increasing sequence captured at schedule time
  }

  private final Map<String, DebounceEntry> debounceEntries = new ConcurrentHashMap<>();
  private final Map<String, String> lastFingerprintByAddress = new ConcurrentHashMap<>();
  // Sequence guards to ensure ordering per address
  private final java.util.concurrent.atomic.AtomicLong globalPublishSeq = new java.util.concurrent.atomic.AtomicLong();
  private final Map<String, Long> lastPublishedSeqByAddress = new ConcurrentHashMap<>();
  private final java.util.Random eventRng = new java.util.Random();

  // Track current run completion subscribers so they can be reset when a new group begins
  private final java.util.Set<io.smallrye.mutiny.subscription.Cancellable> groupCompletionSubscribers =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

  // === Consumer registration for statistics updates ===
  private final java.util.List<java.util.function.Consumer<com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot>> statsConsumers = new java.util.concurrent.CopyOnWriteArrayList<>();
  // separate debounce maps for consumer notifications
  private final java.util.Map<String, DebounceEntry> consumerDebounce = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Map<String, String> consumerLastFp = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Register a consumer to receive debounced manager statistics updates. Returns an AutoCloseable to unregister.
   */
  public java.lang.AutoCloseable onStatisticsUpdated(java.util.function.Consumer<com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot> consumer)
  {
    if (consumer == null)
    {
      return () -> {
      };
    }
    statsConsumers.add(consumer);
    // Ensure at least one snapshot is sent soon after registration
    scheduleAggregatePublish();
    return () -> statsConsumers.remove(consumer);
  }

  /**
   * Cancel and clear all registered completion handlers for the current run. Called when a new group begins.
   */
  private void resetGroupCompletionHandlers()
  {
    try
    {
      for (io.smallrye.mutiny.subscription.Cancellable c : groupCompletionSubscribers)
      {
        try { if (c != null) { c.cancel(); } } catch (Throwable ignored) {}
      }
    }
    catch (Throwable ignored)
    {
    }
    finally
    {
      try { groupCompletionSubscribers.clear(); } catch (Throwable ignored) {}
    }
  }

  public MultiTimedComPortSender()
  {
    log.info("🚀 Initializing CerialMaster MultiTimedComPortSender manager");
    this.rawStatus = Multi.createFrom()
                         .emitter((MultiEmitter<? super ManagerStatus> e) -> this.statusEmitters.add(e))
                         .onOverflow()
                         .drop()
                         .emitOn(Infrastructure.getDefaultExecutor());
    this.rawProgress = Multi.createFrom()
                           .emitter((MultiEmitter<? super ManagerMessageProgress> e) -> this.progressEmitters.add(e))
                           .onOverflow()
                           .drop()
                           .emitOn(Infrastructure.getDefaultExecutor());
    emitStatus(null, TimedComPortSender.State.Idle, "Manager initialized");
    // Expose status/progress as streams that always start with a benign item for new subscribers
    this.status = Multi.createBy()
                      .merging()
                      .streams(
                          Multi.createFrom()
                              .item(new ManagerStatus(null, TimedComPortSender.State.Idle, "Subscribed")),
                          rawStatus
                      );
    this.progress = Multi.createBy()
                        .merging()
                        .streams(
                            Multi.createFrom()
                                .item(new ManagerMessageProgress(null,
                                    new MessageProgress(null, null, null, 0, TimedComPortSender.State.Idle,
                                        new Config(), new Config(), "Subscribed"))),
                            rawProgress
                        );
    log.info("✅ MultiTimedComPortSender manager initialized");
  }

  public Multi<ManagerStatus> status()
  {
    return status;
  }

  public Multi<ManagerMessageProgress> messageProgress()
  {
    return progress;
  }

  /**
   * Ensure a sender exists for the given port using the provided base config. Returns the existing or created sender.
   */
  public TimedComPortSender getOrCreateSender(Integer comPort, Config baseConfig)
  {
    Objects.requireNonNull(comPort, "comPort");
    log.trace("📋 Ensuring sender for COM{} (baseConfig provided: {})", comPort, baseConfig != null);
    // Ensure the COM port is registered via the service (registers into registry and attaches timed sender)
    ComPortConnection<?> conn = null;
    try
    {
      com.guicedee.activitymaster.cerialmaster.client.services.ICerialMasterService<?> svc = com.guicedee.client.IGuiceContext.get(com.guicedee.activitymaster.cerialmaster.client.services.ICerialMasterService.class);
      log.trace("📚 Retrieving ComPortConnection via service for COM{}", comPort);
      // Wait up to 50s as other tests do; environment dependent
      conn = svc.getComPortConnectionDirect(comPort)
                 .await()
                 .atMost(java.time.Duration.ofSeconds(50));
    }
    catch (Throwable t)
    {
      log.warn("⚠️ Service retrieval for COM{} failed, using registry fallback: {}", comPort, t.getMessage());
      // Fallback to direct registry in case service is unavailable in this context
      conn = ComPortConnection.getOrCreate(comPort, null);
    }
    if (conn == null)
    {
      log.warn("⚠️ ComPortConnection was null for COM{}, creating via registry", comPort);
      conn = ComPortConnection.getOrCreate(comPort, null);
    }
    boolean[] isNew = new boolean[]{false};
    ComPortConnection<?> finalConn = conn;
    TimedComPortSender s = senders.computeIfAbsent(comPort, p -> {
      isNew[0] = true;
      return finalConn.getOrCreateTimedSender(baseConfig != null ? baseConfig : new Config());
    });
    if (isNew[0])
    {
      log.trace("✅ Sender created for COM{}", comPort);
      orderedPorts.add(comPort);
    }
    if (baseConfig != null)
    {
      log.trace("📝 Updating config for COM{}", comPort);
      s.updateConfig(baseConfig);
    }
    // Propagate before-start config hook if present
    try { if (this.beforeStartConfig != null) { s.setBeforeStartConfig(this.beforeStartConfig); } } catch (Throwable ignored) {}
    // Attach streams if not attached yet (idempotent attach using a marker list per sender)
    attachToSenderStreams(comPort, s);
    // Immediately publish a first snapshot for this sender's position if new
    if (isNew[0])
    {
      publishSenderSummary(comPort);
    }
    return s;
  }

  private final Set<Integer> attached = ConcurrentHashMap.newKeySet();

  private void attachToSenderStreams(Integer port, TimedComPortSender s)
  {
    if (!attached.add(port))
    {
      return; // already attached
    }
    log.trace("🔗 Attaching to sender streams for COM{}", port);
    s.status()
        .subscribe()
        .with(upd -> {
          emitStatus(port, upd.state, "[port=" + port + "] " + upd.message);
          // publish snapshot to its positional topic on any status change
          publishSenderSummary(port);
          // also publish aggregate (debounced)
          scheduleAggregatePublish();
        })
    ;
    s.messageProgress()
        .subscribe()
        .with(mp -> {
          emitProgress(port, mp);
          onChildMessageProgress(port, mp);
          // publish snapshot to its positional topic on any message progress
          publishSenderSummary(port);
          // also publish aggregate (debounced)
          scheduleAggregatePublish();
        })
    ;
    // Emit benign initial events to guarantee at least one item for new subscribers
    try
    {
      Config cfg = s.getConfig();
      emitStatus(port, TimedComPortSender.State.Idle, "Sender attached");
      emitProgress(port, new MessageProgress(null, null, null, 0, TimedComPortSender.State.Idle, cfg, cfg, "Sender attached"));
      log.trace("✅ Sender streams attached for COM{}", port);
    }
    catch (Throwable t)
    {
      log.warn("⚠️ Failed to emit initial attach events for COM{}: {}", port, t.getMessage());
    }
  }

  // ====== Enqueue APIs ======

  /**
   * Enqueue a list of messages for one COM port as a FIFO group.
   */
  public Uni<GroupResult> enqueueGroup(Integer comPort, List<MessageSpec> specs, Config baseConfig)
  {
    TimedComPortSender sender = getOrCreateSender(comPort, baseConfig);
    int count = (specs == null ? 0 : specs.size());
    log.trace("🚀 Enqueueing group on COM{} with {} messages{}", comPort, count, pausedAll.get() ? " (manager paused)" : "");
    emitStatus(comPort, TimedComPortSender.State.Running, "Enqueue group of " + count);
    if (specs == null || specs.isEmpty())
    {
      log.warn("⚠️ Enqueue called with empty spec list for COM{}", comPort);
      return Uni.createFrom()
                 .item(new GroupResult(List.of()));
    }

    // If no multi-port plan is active, initialize a minimal aggregate plan for this single-port run only.
    // Important: Do NOT override when a multi-port run has already initialized aggregate state in enqueueGroups().
    boolean shouldInitSinglePortPlan = false;
    synchronized (aggLock)
    {
      // Start a fresh single-port run if nothing is planned yet or previous run is not active
      shouldInitSinglePortPlan = (this.plannedByPort == null || this.plannedByPort.isEmpty()) && !this.runActive;
      if (shouldInitSinglePortPlan)
      {
        resetGroupCompletionHandlers();
        this.plannedByPort = new LinkedHashMap<>();
        this.plannedByPort.put(comPort, List.copyOf(specs));
        this.plannedBaseConfig = (baseConfig != null) ? baseConfig : new Config();
        this.plannedGroupProperties = java.util.Map.of();
        this.attemptsByMessage.clear();
        this.terminalMessages.clear();
        this.messageStartNanos.clear();
        this.messageEndNanos.clear();
        this.failures.clear();
        this.runActive = true;
        this.runStartNanos = System.nanoTime();
        this.runStartEpochMs = System.currentTimeMillis();
        // compute initial worst-case for this single port (sum of remaining on this port)
        long portWorst = 0L;
        for (MessageSpec spec : specs)
        {
          if (spec != null)
          {
            long msgBudget = retryBudgetForMessage(spec);
            portWorst += (msgBudget * delayMsForMessage(spec) + timeoutMsForMessage(spec));
          }
        }
        this.runInitialWorstRemainingMs = Math.max(0L, portWorst);
        this.aggFuture = new java.util.concurrent.CompletableFuture<>();
      }
    }
    // Publish an initial aggregate snapshot if we started a single-port plan
    if (shouldInitSinglePortPlan)
    {
      try
      {
        // Reset child sender status so its flags are fresh for this new single-port run
        try { sender.resetForRetry("New group starting – status reset"); } catch (Throwable ignored) {}
        publishSenderSummary(comPort);
      }
      catch (Throwable ignored) {}
      scheduleAggregatePublish();
    }
    // If globally paused, also pause the sender right away
    if (pausedAll.get())
    {
      sender.pause();
    }
    // If the sender is currently running a group/message, cancel it, wait 200ms, then start the next group
    if (sender.hasActiveGroupOrMessage())
    {
      try { sender.cancel("New group enqueued – cancelling current", true); } catch (Throwable ignored) {}
      return Uni.createFrom().voidItem()
          .onItem().delayIt().by(Duration.ofMillis(20))
          .onItem().transformToUni(v -> sender.enqueueGroup(specs));
    }
    return sender.enqueueGroup(specs);
  }

  /**
   * Enqueue groups per port and return a Uni completing when all ports finish their groups.
   */
  public Uni<Map<Integer, GroupResult>> enqueueGroups(Map<Integer, List<MessageSpec>> byPort, Config baseConfig)
  {
    log.trace("🚀 Enqueueing groups for {} ports", byPort == null ? 0 : byPort.size());
    return enqueueGroups(byPort, baseConfig, java.util.Map.of());
  }

  /**
   * Enqueue groups per port with custom properties, returning a Uni when all ports finish their groups.
   */
  public Uni<Map<Integer, GroupResult>> enqueueGroups(Map<Integer, List<MessageSpec>> byPort,
                                                      Config baseConfig,
                                                      Map<String, Boolean> properties)
  {
    if (byPort == null || byPort.isEmpty())
    {
      log.warn("⚠️ enqueueGroups called with empty port map");
      // Initialize and finalize an empty run so that aggregate resets and completes at 100%
      synchronized (aggLock)
      {
        resetGroupCompletionHandlers();
        this.plannedByPort = new LinkedHashMap<>();
        this.plannedBaseConfig = baseConfig != null ? baseConfig : new Config();
        this.plannedGroupProperties = (properties == null) ? java.util.Map.of() : new java.util.LinkedHashMap<>(properties);
        this.attemptsByMessage.clear();
        this.terminalMessages.clear();
        this.messageStartNanos.clear();
        this.messageEndNanos.clear();
        this.failures.clear();
        this.runActive = true;
        this.runStartNanos = System.nanoTime();
        this.runStartEpochMs = System.currentTimeMillis();
        this.runInitialWorstRemainingMs = 0L;
        this.aggFuture = new CompletableFuture<>();
      }
      // publish initial aggregate and then finalize immediately (no ports/messages)
      scheduleAggregatePublish();
      finalizeAggregate();
      return Uni.createFrom()
                 .item(Collections.emptyMap());
    }
    log.trace("🔄 Running enqueueGroups for {} ports", byPort.size());
    // Initialize aggregate tracking for this run
    synchronized (aggLock)
    {
      resetGroupCompletionHandlers();
      this.plannedByPort = new LinkedHashMap<>();
      byPort.forEach((k, v) -> this.plannedByPort.put(k, v == null ? List.of() : List.copyOf(v)));
      this.plannedBaseConfig = baseConfig != null ? baseConfig : new Config();
      this.plannedGroupProperties = (properties == null) ? java.util.Map.of() : new java.util.LinkedHashMap<>(properties);
      this.attemptsByMessage.clear();
      this.terminalMessages.clear();
      this.messageStartNanos.clear();
      this.messageEndNanos.clear();
      this.failures.clear();
      this.runActive = true;
      this.runStartNanos = System.nanoTime();
      this.runStartEpochMs = System.currentTimeMillis();
      // Proactively reset child sender status/history for all involved ports so per-port error flags reset
      try {
        for (Integer port : this.plannedByPort.keySet()) {
          try {
            TimedComPortSender s = getOrCreateSender(port, this.plannedBaseConfig);
            if (s != null) {
              s.resetForRetry("New group starting – status reset");
            }
            publishSenderSummary(port);
          } catch (Throwable ignored) {}
        }
      } catch (Throwable ignored) {}
      // compute initial overall worst-case duration across ports (max of per-port sums)
      long initialMax = 0L;
      for (Map.Entry<Integer, List<MessageSpec>> e : this.plannedByPort.entrySet())
      {
        long portWorst = 0L;
        List<MessageSpec> specs = e.getValue();
        if (specs != null)
        {
          for (MessageSpec spec : specs)
          {
            com.guicedee.activitymaster.cerialmaster.client.Config cfg = (spec != null && spec.getConfig() != null) ? spec.getConfig() : (this.plannedBaseConfig != null ? this.plannedBaseConfig : new Config());
            portWorst += (long) cfg.getAssignedRetry() * cfg.assignedDelayMs + cfg.assignedTimeoutMs;
          }
        }
        if (portWorst > initialMax)
        {
          initialMax = portWorst;
        }
      }
      this.runInitialWorstRemainingMs = initialMax;
      this.aggFuture = new CompletableFuture<>();
    }
    // initial aggregate publish (debounced)
    scheduleAggregatePublish();
    List<Integer> ports = new ArrayList<>(byPort.keySet());
    List<Uni<GroupResult>> unis = new ArrayList<>();
    for (Integer p : ports)
    {
      // Ensure each port Uni never fails so the combined Uni only completes
      // after ALL ports have either completed or errored (connection errors included).
      // This satisfies the requirement that the overall "completed" signal only fires
      // after all senders are done or have a connection error, instead of failing fast.
      Uni<GroupResult> safeUni = enqueueGroup(p, byPort.get(p), baseConfig)
          .onFailure()
          .recoverWithItem(err -> {
            try
            {
              log.error("❌ Port {} group encountered error: {}", p, err.toString());
            }
            catch (Throwable ignored)
            {
            }
            // Return an empty GroupResult as a sentinel for error on this port.
            // The aggregate/finalization will still run when all ports have settled.
            return new GroupResult(java.util.List.of());
          });
      unis.add(safeUni);
    }
    Uni<Map<Integer, GroupResult>> combined = Uni.combine()
                                                  .all()
                                                  .unis(unis)
                                                  .with(results -> {
                                                    Map<Integer, GroupResult> map = new LinkedHashMap<>();
                                                    for (int i = 0; i < ports.size(); i++)
                                                    {
                                                      map.put(ports.get(i), (GroupResult) results.get(i));
                                                    }
                                                    return map;
                                                  })
        ;
    // When all groups finish, finalize aggregate (manual retries should be started by explicitly enqueuing a new group)
    combined.subscribe()
        .with(res -> finalizeAggregate(), err -> {
          log.error("❌ Error during enqueueGroups processing: {}", err.getMessage(), err);
          finalizeAggregate();
        });
    return combined;
  }

  /**
   * Re-enqueue only the failed messages from the most recent run, rebuilding their MessageSpec from the last plan.
   * This method resets aggregate tracking (same as a new enqueueGroups call) and returns a Uni completing when
   * the retry groups have finished. If no prior run or no failures are present, the aggregate is still reset
   * (using empty lists per last-known port) and the returned Uni will complete quickly.
   */
  public Uni<Map<Integer, GroupResult>> retryLastFailures()
  {
    Map<Integer, List<MessageSpec>> lastPlan = this.plannedByPort;
    List<com.guicedee.activitymaster.cerialmaster.client.Failure> failsSnapshot = new ArrayList<>(this.failures);
    log.info("🔄 Retrying last failures — previous failures: {}", failsSnapshot.size());
    if (lastPlan == null || lastPlan.isEmpty())
    {
      return Uni.createFrom()
                 .failure(new IllegalStateException("No previous run plan is available to retry"));
    }
    // Before computing retries, proactively reset child sender statuses on all ports from the last plan
    try
    {
      for (Integer port : lastPlan.keySet())
      {
        try
        {
          TimedComPortSender s = getOrCreateSender(port, this.plannedBaseConfig);
          if (s != null)
          {
            s.resetForRetry("Retrying last failures: reset status");
          }
          // Also emit a manager-level reset status for visibility
          emitStatus(port, TimedComPortSender.State.Idle, "Retry requested – status reset");
          // publish an immediate summary for UI refresh
          publishSenderSummary(port);
        }
        catch (Throwable ignored)
        {
        }
      }
    }
    catch (Throwable ignored)
    {
    }
    // Build lookup id->spec per port
    Map<Integer, Map<String, MessageSpec>> lookup = new LinkedHashMap<>();
    for (Map.Entry<Integer, List<MessageSpec>> e : lastPlan.entrySet())
    {
      Map<String, MessageSpec> m = new HashMap<>();
      List<MessageSpec> specs = e.getValue();
      if (specs != null)
      {
        for (MessageSpec s : specs)
        {
          if (s != null && s.getId() != null)
          {
            m.put(s.getId(), s);
          }
        }
      }
      lookup.put(e.getKey(), m);
    }
    // Build retry map
    Map<Integer, List<MessageSpec>> retryMap = new LinkedHashMap<>();
    for (com.guicedee.activitymaster.cerialmaster.client.Failure f : failsSnapshot)
    {
      Map<String, MessageSpec> m = lookup.get(f.comPort);
      if (m == null)
      {
        continue;
      }
      MessageSpec spec = m.get(f.messageId);
      if (spec != null)
      {
        // de-dup per port
        retryMap.computeIfAbsent(f.comPort, k -> new ArrayList<>());
        List<MessageSpec> lst = retryMap.get(f.comPort);
        boolean exists = lst.stream()
                             .anyMatch(ms -> Objects.equals(ms.getId(), spec.getId()));
        if (!exists)
        {
          lst.add(spec);
        }
      }
    }
    // If no failures were found, still reset aggregation by creating an empty per-port plan
    if (retryMap.isEmpty())
    {
      for (Integer port : lastPlan.keySet())
      {
        retryMap.put(port, List.of());
      }
    }
    return enqueueGroups(retryMap, this.plannedBaseConfig);
  }

  /**
   * Convenience to set name and retry last failures in one call.
   */
  public Uni<Map<Integer, GroupResult>> retryLastFailuresWithName(String name)
  {
    setGroupName(name);
    return retryLastFailures();
  }

  // ====== Aggregate tracking helpers ======

  private void onChildMessageProgress(Integer port, MessageProgress mp)
  {
    if (!runActive || mp == null)
    {
      return;
    }

    // Resolve an id to use for accounting. If missing, map the next pending spec id for this port.
    String id = mp.id;
    if (id == null || id.isBlank())
    {
      try
      {
        Map<Integer, List<MessageSpec>> plan = plannedByPort;
        if (plan != null && port != null)
        {
          List<MessageSpec> lst = plan.get(port);
          if (lst != null)
          {
            for (MessageSpec s : lst)
            {
              if (s == null)
              {
                continue;
              }
              String sid = s.getId();
              if (sid != null && !sid.isBlank() && !terminalMessages.contains(sid))
              {
                id = sid;
                break;
              }
            }
          }
        }
      }
      catch (Throwable ignored)
      {
      }
    }

    // Track first start time and remember id->port
    if (mp.note != null && mp.note.contains("Starting"))
    {
      if (id != null)
      {
        messageStartNanos.putIfAbsent(id, System.nanoTime());
        if (port != null)
        {
          messageIdToPort.put(id, port);
        }
      }
    }
    // Track attempts used
    if (id != null)
    {
      attemptsByMessage.put(id, Math.max(0, mp.attempt));
    }
    // Track terminal and failures; clear id->port mapping on terminal
    if (mp.state == TimedComPortSender.State.Completed || mp.state == TimedComPortSender.State.TimedOut
            || mp.state == TimedComPortSender.State.Cancelled || mp.state == TimedComPortSender.State.Error)
    {
      boolean newlyTerminal = (id != null) && terminalMessages.add(id);
      if (newlyTerminal)
      {
        messageEndNanos.put(id, System.nanoTime());
        if (mp.state != TimedComPortSender.State.Completed)
        {
          String fname = null;
          try
          {
            // derive friendly name from latest progress/title if possible, else from planned spec
            if (false)
            {
              //if (mp.title != null && !mp.title.isBlank()) {
              fname = mp.title;
            }
            else
            {
              Map<Integer, List<MessageSpec>> plan = plannedByPort;
              if (plan != null)
              {
                for (List<MessageSpec> lst : plan.values())
                {
                  if (lst == null)
                  {
                    continue;
                  }
                  for (MessageSpec s : lst)
                  {
                    if (s != null && java.util.Objects.equals(s.getId(), id))
                    {
                      String fn = s.getFriendlyName();
                      if (fn != null && !fn.isBlank())
                      {
                        fname = fn;
                      }
                      else if (s.getTitle() != null && !s.getTitle().isBlank())
                      {
                        fname = s.getTitle();
                      }
                      else
                      {
                        fname = s.getId();
                      }
                      break;
                    }
                  }
                }
              }
            }
          }
          catch (Throwable ignored)
          {
          }
          failures.add(new Failure(port, id, fname, mp.state));
        }
      }
      if (id != null)
      {
        messageIdToPort.remove(id);
      }
      // If this message just reached terminal, check if all planned messages are now terminal; if so, finalize aggregate.
      try
      {
        Map<Integer, List<MessageSpec>> byPort = this.plannedByPort;
        if (byPort != null && !byPort.isEmpty())
        {
          int plannedTotal = byPort.values()
                                 .stream()
                                 .mapToInt(lst -> lst == null ? 0 : lst.size())
                                 .sum();
          if (plannedTotal > 0)
          {
            int terminalCount = terminalMessages.size();
            if (terminalCount >= plannedTotal && runActive)
            {
              finalizeAggregate();
            }
          }
        }
      }
      catch (Throwable ignored)
      {
      }
    }
  }

  private void finalizeAggregate()
  {
    log.trace("🎉 Finalizing run aggregate");
    AggregateProgress snap = computeAggregateSnapshot(true);
    // record finish time for idle detection
    if (snap != null && snap.finishedAtEpochMs != null)
    {
      lastRunFinishedAtEpochMs = snap.finishedAtEpochMs;
    }
    else
    {
      lastRunFinishedAtEpochMs = System.currentTimeMillis();
    }
    CompletableFuture<AggregateProgress> f = this.aggFuture;
    if (f != null && !f.isDone())
    {
      f.complete(snap);
    }
    synchronized (aggLock)
    {
      runActive = false;
    }
    // After finishing, dispose of success-related per-id references; keep failures for retry
    try
    {
      Map<Integer, List<MessageSpec>> byPort = this.plannedByPort != null ? this.plannedByPort : Map.of();
      Set<String> failedIds = this.failures.stream()
                                  .map(fl -> fl.messageId)
                                  .filter(Objects::nonNull)
                                  .collect(Collectors.toSet())
          ;
      for (List<MessageSpec> specs : byPort.values())
      {
        if (specs == null)
        {
          continue;
        }
        for (MessageSpec s : specs)
        {
          if (s == null || s.getId() == null)
          {
            continue;
          }
          if (!failedIds.contains(s.getId()))
          {
            attemptsByMessage.remove(s.getId());
            messageStartNanos.remove(s.getId());
            messageEndNanos.remove(s.getId());
            // messageIdToPort is already cleared on terminal events
          }
        }
      }
    }
    catch (Throwable ignored)
    {
    }
    // publish final aggregate (debounced but soon)
    scheduleAggregatePublish();
    // Emit terminal benign events to ensure subscribers observe at least one item, even in edge timings
    try
    {
      emitStatus(null, TimedComPortSender.State.Completed, "Run finalized");
      Config cfg = plannedBaseConfig != null ? plannedBaseConfig : new Config();
      emitProgress(null, new MessageProgress(null, null, null, 0, TimedComPortSender.State.Completed, cfg, cfg, "Run finalized"));
      // Also emit follow-up events shortly after to ensure late-awaiting subscribers see an item
      try {
        java.util.concurrent.TimeUnit unit = java.util.concurrent.TimeUnit.MILLISECONDS;
        eventScheduler.schedule(() -> {
          try {
            emitStatus(null, TimedComPortSender.State.Completed, "Run finalized (post)");
            Config cfg2 = plannedBaseConfig != null ? plannedBaseConfig : new Config();
            emitProgress(null, new MessageProgress(null, null, null, 0, TimedComPortSender.State.Completed, cfg2, cfg2, "Run finalized (post)"));
          } catch (Throwable ignored1) {}
        }, 120, unit);
        eventScheduler.schedule(() -> {
          try {
            emitStatus(null, TimedComPortSender.State.Completed, "Run finalized (post-2)");
            Config cfg3 = plannedBaseConfig != null ? plannedBaseConfig : new Config();
            emitProgress(null, new MessageProgress(null, null, null, 0, TimedComPortSender.State.Completed, cfg3, cfg3, "Run finalized (post-2)"));
          } catch (Throwable ignored1) {}
        }, 300, unit);
        eventScheduler.schedule(() -> {
          try {
            emitStatus(null, TimedComPortSender.State.Completed, "Run finalized (post-3)");
            Config cfg4 = plannedBaseConfig != null ? plannedBaseConfig : new Config();
            emitProgress(null, new MessageProgress(null, null, null, 0, TimedComPortSender.State.Completed, cfg4, cfg4, "Run finalized (post-3)"));
          } catch (Throwable ignored1) {}
        }, 600, unit);
      } catch (Throwable ignored2) {}
    }
    catch (Throwable ignored)
    {
    }
  }


  private long retryBudgetForMessage(MessageSpec spec)
  {
    com.guicedee.activitymaster.cerialmaster.client.Config cfg = (spec != null && spec.getConfig() != null) ? spec.getConfig() : (plannedBaseConfig != null ? plannedBaseConfig : new Config());
    return Math.max(0, cfg.getAssignedRetry());
  }

  private long delayMsForMessage(MessageSpec spec)
  {
    com.guicedee.activitymaster.cerialmaster.client.Config cfg = (spec != null && spec.getConfig() != null) ? spec.getConfig() : (plannedBaseConfig != null ? plannedBaseConfig : new Config());
    return Math.max(0, cfg.assignedDelayMs);
  }

  private long timeoutMsForMessage(MessageSpec spec)
  {
    com.guicedee.activitymaster.cerialmaster.client.Config cfg = (spec != null && spec.getConfig() != null) ? spec.getConfig() : (plannedBaseConfig != null ? plannedBaseConfig : new Config());
    return Math.max(0, cfg.assignedTimeoutMs);
  }

  AggregateProgress computeAggregateSnapshot(boolean finishing)
  {
    Map<Integer, List<MessageSpec>> byPort = this.plannedByPort;
    if (byPort == null)
    {
      byPort = Map.of();
    }
    int totalPorts = byPort.size();
    int totalMessages = byPort.values()
                            .stream()
                            .mapToInt(l -> l == null ? 0 : l.size())
                            .sum()
        ;
    long totalTasksBudget = byPort.entrySet()
                                .stream()
                                .mapToLong(e -> e.getValue()
                                                    .stream()
                                                    .mapToLong(this::retryBudgetForMessage)
                                                    .sum())
                                .sum()
        ;

    // Per-port stats
    Map<Integer, PortDetail> perPort = new LinkedHashMap<>();
    long tasksRemaining = 0;
    long overallWorstRemainingMsAcrossPorts = 0;
    int messagesCompletedOverall = 0;
    long timeSavedMs = 0;

    for (Map.Entry<Integer, List<MessageSpec>> entry : byPort.entrySet())
    {
      Integer port = entry.getKey();
      List<MessageSpec> specs = entry.getValue();
      long portBudget = 0;
      long portRemainingTasks = 0;
      long portWorstRemainingMs = 0;
      int portMessages = specs.size();
      int portCompleted = 0;

      for (MessageSpec spec : specs)
      {
        portBudget += retryBudgetForMessage(spec);
        String id = spec.getId();
        int attemptsUsed = attemptsByMessage.getOrDefault(id, 0);
        long msgBudget = retryBudgetForMessage(spec);
        boolean isTerminal = terminalMessages.contains(id);
        if (isTerminal)
        {
          portCompleted++;
          messagesCompletedOverall++;
        }
        // tasks remaining for this message (as messages x retries definition)
        long remainingTasksForMsg = isTerminal ? 0 : Math.max(0, msgBudget - Math.min(msgBudget, attemptsUsed));
        portRemainingTasks += remainingTasksForMsg;

        // Worst-case remaining time for this message if it times out from now
        long remainingRetries = isTerminal ? 0 : Math.max(0, msgBudget - Math.min(msgBudget, attemptsUsed));
        long worstForMsgMs = isTerminal ? 0 : (remainingRetries * delayMsForMessage(spec) + timeoutMsForMessage(spec));
        // Sum across queued (serial on a port)
        portWorstRemainingMs += worstForMsgMs;

        // Time saved for early completion
        if (isTerminal)
        {
          Long startN = messageStartNanos.get(id);
          Long endN = messageEndNanos.get(id);
          if (startN != null && endN != null)
          {
            long elapsedMs = Math.max(0, (endN - startN) / 1_000_000);
            long worstTotalForMsg = msgBudget * delayMsForMessage(spec) + timeoutMsForMessage(spec);
            long saved = Math.max(0, worstTotalForMsg - elapsedMs);
            timeSavedMs += saved;
          }
        }
      }
      double portPercent = portBudget == 0 ? 100.0 : ((portBudget - portRemainingTasks) * 100.0) / portBudget;
      boolean portAnyErrored = failures.stream()
                                   .anyMatch(f -> Objects.equals(f.comPort, port));
      boolean portAnyWaitingForResponse = messageIdToPort.values()
                                              .stream()
                                              .anyMatch(p -> Objects.equals(p, port))
          ;
      perPort.put(port, new PortDetail(port, portMessages, portCompleted, portPercent, portBudget, portRemainingTasks, portWorstRemainingMs, portWorstRemainingMs, portAnyErrored, portAnyWaitingForResponse));
      tasksRemaining += portRemainingTasks;
      overallWorstRemainingMsAcrossPorts = Math.max(overallWorstRemainingMsAcrossPorts, portWorstRemainingMs);
    }

    double percentCompleteOverall = totalTasksBudget == 0 ? 100.0 : ((totalTasksBudget - tasksRemaining) * 100.0) / totalTasksBudget;
    boolean anyFailures = !failures.isEmpty();
    boolean anyErrored = anyFailures;
    // Safety: infer terminal state only when ALL messages have reached a terminal state.
    // Using tasksRemaining (based on retry budgets) can be misleading during pauses or mixed retries,
    // so rely on completed message count to avoid premature finishing during paused runs.
    boolean inferredFinish = !finishing
        && (totalMessages > 0)
        && (messagesCompletedOverall == totalMessages)
        && (runStartEpochMs > 0);
    Long finishedAt = (finishing || inferredFinish) ? System.currentTimeMillis() : null;

    // On finishing snapshot, force remaining/time to 0 and percent to 100% for deterministic terminal aggregates
    if (finishing || inferredFinish)
    {
      tasksRemaining = 0;
      overallWorstRemainingMsAcrossPorts = 0;
      percentCompleteOverall = 100.0;
      // also zero out per-port remaining/time
      if (perPort != null && !perPort.isEmpty())
      {
        for (Map.Entry<Integer, PortDetail> e : perPort.entrySet())
        {
          PortDetail pd = e.getValue();
          if (pd != null)
          {
            pd.tasksRemaining = 0;
            pd.worstCaseRemainingMs = 0;
            pd.timeRemainingMs = 0;
          }
        }
      }
    }

    Long estimatedFinish = finishedAt != null ? finishedAt : (runStartEpochMs > 0 ? System.currentTimeMillis() + overallWorstRemainingMsAcrossPorts : null);
    Long originalEstimatedFinish = runStartEpochMs > 0 ? (runStartEpochMs + runInitialWorstRemainingMs) : null;

    boolean anyWaitingForResponse = runActive && !messageIdToPort.isEmpty();
    Map<String, Boolean> props = plannedGroupProperties != null ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(plannedGroupProperties)) : java.util.Map.of();

    // Determine effective group name applying idle rules
    String effectiveName = this.groupName;
    boolean shouldIdleName = false;
    if (effectiveName == null || effectiveName.isBlank())
    {
      shouldIdleName = true;
    }
    else if (!runActive)
    {
      long lastFinish = lastRunFinishedAtEpochMs;
      if (lastFinish > 0)
      {
        long elapsed = System.currentTimeMillis() - lastFinish;
        if (elapsed >= idleAfterMs())
        {
          shouldIdleName = true;
        }
      }
    }
    if (shouldIdleName)
    {
      effectiveName = "Idle";
    }

    return new AggregateProgress(
        effectiveName,
        runStartEpochMs,
        finishedAt,
        totalPorts,
        totalMessages,
        totalTasksBudget,
        tasksRemaining,
        anyFailures,
        anyErrored,
        List.copyOf(failures),
        percentCompleteOverall,
        Collections.unmodifiableMap(perPort),
        overallWorstRemainingMsAcrossPorts,
        overallWorstRemainingMsAcrossPorts,
        estimatedFinish,
        originalEstimatedFinish,
        timeSavedMs,
        props,
        anyWaitingForResponse
    );
  }

  /**
   * Returns a Uni that completes with the aggregate progress for the current run when it finishes.
   */
  public Uni<AggregateProgress> currentRunAggregateUni()
  {
    CompletableFuture<AggregateProgress> f = this.aggFuture;
    if (f == null)
    {
      return Uni.createFrom()
                 .failure(new IllegalStateException("No active run to aggregate"));
    }
    return Uni.createFrom()
               .completionStage(f);
  }

  /**
   * Register a consumer to be notified when the currently enqueued task group completes.
   * The consumer receives the final AggregateProgress snapshot. The returned handle can
   * be closed to cancel the subscription before completion.
   */
  public java.lang.AutoCloseable onGroupCompleted(java.util.function.Consumer<AggregateProgress> consumer)
  {
    return onGroupCompleted(consumer, t -> {
      try { log.error("Error in onGroupCompleted subscriber: {}", t.toString(), t); } catch (Throwable ignored) {}
    });
  }

  /**
   * Register a consumer to be notified when the currently enqueued task group completes,
   * with an error handler for subscription failures.
   */
  public java.lang.AutoCloseable onGroupCompleted(java.util.function.Consumer<AggregateProgress> consumer,
                                                  java.util.function.Consumer<java.lang.Throwable> onError)
  {
    if (consumer == null)
    {
      return () -> {};
    }
    io.smallrye.mutiny.subscription.Cancellable cancellable = null;
    try
    {
      cancellable = currentRunAggregateUni()
          .emitOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultExecutor())
          .subscribe()
          .with(consumer, t -> {
            try { if (onError != null) { onError.accept(t); } } catch (Throwable ignored) {}
          });
      if (cancellable != null) {
        groupCompletionSubscribers.add(cancellable);
      }
    }
    catch (Throwable t)
    {
      try { if (onError != null) { onError.accept(t); } } catch (Throwable ignored) {}
    }
    io.smallrye.mutiny.subscription.Cancellable finalCancellable = cancellable;
    return () -> {
      try { if (finalCancellable != null) { finalCancellable.cancel(); groupCompletionSubscribers.remove(finalCancellable); } } catch (Throwable ignored) {}
    };
  }

  /**
   * Convenience: set the group name and enqueue groups.
   */
  public Uni<Map<Integer, GroupResult>> enqueueGroupsWithName(String name, Map<Integer, List<MessageSpec>> byPort, Config baseConfig)
  {
    setGroupName(name);
    return enqueueGroups(byPort, baseConfig);
  }

  /**
   * Convenience: set the group name, set properties, then enqueue groups.
   */
  public Uni<Map<Integer, GroupResult>> enqueueGroupsWithName(String name,
                                                              Map<Integer, List<MessageSpec>> byPort,
                                                              Config baseConfig,
                                                              Map<String, Boolean> properties)
  {
    setGroupName(name);
    return enqueueGroups(byPort, baseConfig, properties);
  }

  // ====== Group controls ======

  /**
   * Pause all managed senders and emit cascaded events.
   */
  public Uni<Void> pauseAll()
  {
    pausedAll.set(true);
    log.info("⏸️ Pausing all senders ({} active)", senders.size());
    emitStatus(null, TimedComPortSender.State.Paused, "Manager pauseAll");
    senders.values()
        .forEach(TimedComPortSender::pause);
    return Uni.createFrom()
               .voidItem();
  }

  /**
   * Resume all managed senders and emit cascaded events.
   */
  public Uni<Void> resumeAll()
  {
    pausedAll.set(false);
    log.info("▶️ Resuming all senders ({} active)", senders.size());
    emitStatus(null, TimedComPortSender.State.Running, "Manager resumeAll");
    senders.values()
        .forEach(TimedComPortSender::resume);
    return Uni.createFrom()
               .voidItem();
  }

  /**
   * Cancel all managed senders and emit cascaded events with a reason.
   */
  public Uni<Void> cancelAll(String reason)
  {
    String prev = getGroupName();
    if (prev == null)
    {
      prev = "";
    }
    // Reflect immediate cancelling state in the group name
    setGroupName("Cancelling : " + prev);
    log.warn("🛑 Cancelling all senders. Reason: {}", reason);
    emitStatus(null, TimedComPortSender.State.Cancelled, "Manager cancelAll: " + (reason == null ? "Cancelled" : reason));
    // Propagate cancellation to all senders
    senders.values().forEach(s -> s.cancel(reason));

    // Ensure final percent is 100% by marking all planned messages as terminal
    try
    {
      Map<Integer, List<MessageSpec>> byPort = this.plannedByPort;
      if (byPort != null && !byPort.isEmpty())
      {
        for (List<MessageSpec> specs : byPort.values())
        {
          if (specs == null) continue;
          for (MessageSpec spec : specs)
          {
            if (spec == null) continue;
            String id = spec.getId();
            if (id != null)
            {
              terminalMessages.add(id);
            }
          }
        }
      }
    }
    catch (Throwable ignored)
    {
    }

    // Immediately finalize the aggregate so snapshots reflect 100% completion on cancel
    finalizeAggregate();

    // After propagating cancellation, indicate final Cancelled state until next task performs
    setGroupName("Cancelled");
    return Uni.createFrom()
               .voidItem();
  }

  // ====== Snapshot API ======

  /**
   * Aggregated manager snapshot that includes current aggregate progress (non-final snapshot)
   * and per-sender snapshots limited as requested (waiting: next 25, sending: 0..1, completed: last 100).
   */

  /**
   * Builds a manager snapshot with default limits waiting=25 and completed=100 per sender.
   */
  public com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot snapshot()
  {
    return snapshot(25, 100);
  }

  /**
   * Builds a manager snapshot with custom limits for waiting and completed lists per sender.
   */
  public com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot snapshot(int waitingLimit, int completedLimit)
  {
    // If the run is no longer active (already finalized), return a terminal aggregate snapshot
    // so finishedAtEpochMs and 100% completion are reflected for callers.
    boolean finishingFlag;
    synchronized (aggLock)
    {
      finishingFlag = !runActive && (runStartEpochMs > 0 || lastRunFinishedAtEpochMs > 0);
    }
    AggregateProgress agg = computeAggregateSnapshot(finishingFlag);
    Map<Integer, SenderSnapshot> map = new LinkedHashMap<>();
    for (Map.Entry<Integer, TimedComPortSender> e : senders.entrySet())
    {
      map.put(e.getKey(),
          e.getValue()
              .snapshot(waitingLimit, completedLimit));
    }
    return new com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot(agg, Collections.unmodifiableMap(map));
  }

  // ====== Utilities ======

  /**
   * Schedule debounced publish of the manager aggregate to 'server-task-updates'.
   */
  private void scheduleAggregatePublish()
  {
    log.trace("📊 Scheduling debounced aggregate publish");
    // Compute once to keep payload and fingerprint consistent per cycle.
    // If the run has already ended, publish a terminal aggregate so percent=100 and finishedAt are set.
    boolean finishingFlag;
    synchronized (aggLock)
    {
      finishingFlag = !runActive && (runStartEpochMs > 0 || lastRunFinishedAtEpochMs > 0);
    }
    final AggregateProgress aggSnapshot = computeAggregateSnapshot(finishingFlag);
    final String aggFp = fingerprintAggregate(aggSnapshot);
    if (publishingEnabled)
    {
      debouncedPublish(aggregatePublishAddress,
          () -> aggSnapshot,
          () -> aggFp
      );
    }
    // Local consumer notifications (debounced) – reuse the same aggregate snapshot to avoid drift
    final com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot managerSnap = buildManagerSnapshot(aggSnapshot, 25, 100);
    final String managerFp = fingerprintManagerSnapshot(managerSnap);
    debouncedConsumer("manager-stats",
        () -> managerSnap,
        () -> managerFp,
        (snap) -> {
          com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot ms = (com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot) snap;
          for (java.util.function.Consumer<com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot> c : statsConsumers)
          {
            try
            {
              c.accept(ms);
            }
            catch (Throwable ignored)
            {
            }
          }
        });
  }

  /**
   * Build a ManagerSnapshot using a precomputed AggregateProgress to ensure consistency between
   * the aggregate published event and the manager-stats consumer notification within the same cycle.
   */
  private com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot buildManagerSnapshot(AggregateProgress agg, int waitingLimit, int completedLimit)
  {
    Map<Integer, SenderSnapshot> map = new LinkedHashMap<>();
    for (Map.Entry<Integer, TimedComPortSender> e : senders.entrySet())
    {
      map.put(e.getKey(), e.getValue().snapshot(waitingLimit, completedLimit));
    }
    return new com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot(agg, Collections.unmodifiableMap(map));
  }

  private String fingerprintSenderSnapshot(SenderSnapshot s)
  {
    if (s == null)
    {
      return "null";
    }
    String sendingId = (s.getSending() == null || s.getSending()
                                                      .getId() == null) ? "-" : s.getSending()
                                                                                    .getId();
    String sendingTitle = (s.getSending() == null || s.getSending()
                                                         .getTitle() == null) ? "-" : s.getSending()
                                                                                          .getTitle();
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

  private String fingerprintAggregate(AggregateProgress a)
  {
    if (a == null)
    {
      return "null";
    }
    return new StringBuilder(128)
               .append(a.groupName)
               .append('|')
               .append(a.totalPorts)
               .append('|')
               .append(a.totalMessages)
               .append('|')
               .append(a.tasksRemaining)
               .append('|')
               .append(a.timeRemainingMs)
               .append('|')
               .append(String.format(java.util.Locale.ROOT, "%.4f", a.percentCompleteOverall))
               .append('|')
               .append(a.finishedAtEpochMs)
               .append('|')
               .append(a.failures == null ? 0 : a.failures.size())
               .toString();
  }

  private String fingerprintManagerSnapshot(com.guicedee.activitymaster.cerialmaster.client.ManagerSnapshot snap)
  {
    if (snap == null)
    {
      return "null";
    }
    StringBuilder sb = new StringBuilder(256);
    sb.append(fingerprintAggregate(snap.aggregate))
        .append('|');
    // maintain order by orderedPorts to keep fingerprint stable
    int count = 0;
    for (Integer p : orderedPorts)
    {
      SenderSnapshot s = snap.perSender.get(p);
      sb.append(p)
          .append('#')
          .append(fingerprintSenderSnapshot(s))
          .append('|')
      ;
      count++;
    }
    // include count of senders in case of none
    sb.append("ports=")
        .append(count);
    return sb.toString();
  }

  /**
   * Returns the 1-based index position for the given port as per insertion order.
   */
  private int indexForPort(Integer port)
  {
    if (port == null)
    {
      return -1;
    }
    int idx = orderedPorts.indexOf(port);
    return idx < 0 ? -1 : (idx + 1);
  }

  /**
   * Debounced publish utility: schedules a publish to the given address in 300-700ms, always sending the latest payload.
   * Consecutive identical fingerprints on the same address are suppressed.
   */
  private void debouncedPublish(String address, java.util.function.Supplier<Object> payloadSupplier, java.util.function.Supplier<String> fingerprintSupplier)
  {
    if (address == null || payloadSupplier == null || fingerprintSupplier == null)
    {
      return;
    }
    DebounceEntry entry = debounceEntries.compute(address, (k, v) -> {
      if (v == null)
      {
        v = new DebounceEntry();
      }
      v.payloadSupplier = payloadSupplier;
      v.fingerprintSupplier = fingerprintSupplier;
      return v;
    });
    // If no task scheduled, schedule one now with random delay 300-700ms
    if (entry.future == null || entry.future.isDone())
    {
      int delay = 300 + eventRng.nextInt(401); // 300..700
      // capture the sequence at schedule time so older schedules are rejected if a newer one ran
      entry.scheduledSeq = globalPublishSeq.incrementAndGet();
      entry.future = eventScheduler.schedule(() -> {
        try
        {
          DebounceEntry e = debounceEntries.get(address);
          if (e == null)
          {
            return;
          }
          long seq = e.scheduledSeq;
          Long lastSeq = lastPublishedSeqByAddress.get(address);
          if (lastSeq != null && seq < lastSeq)
          {
            // stale scheduled task, drop
            return;
          }
          Object payload = null;
          String fp = null;
          try
          {
            payload = e.payloadSupplier.get();
            fp = e.fingerprintSupplier.get();
          }
          catch (Throwable ignored)
          {
          }
          if (fp == null)
          {
            fp = String.valueOf(System.identityHashCode(payload));
          }
          String last = lastFingerprintByAddress.get(address);
          if (!Objects.equals(last, fp))
          {
            log.debug("📤 Publishing to address '{}' (debounced)", address);
            publishToAddress(address, payload);
            lastFingerprintByAddress.put(address, fp);
            lastPublishedSeqByAddress.put(address, seq);
          }
          else
          {
            // even if suppressed by fingerprint, advance sequence to prevent older publishes
            lastPublishedSeqByAddress.put(address, seq);
          }
        }
        catch (Throwable ignored)
        {
        }
        finally
        {
          // allow next schedule
          DebounceEntry e2 = debounceEntries.get(address);
          if (e2 != null)
          {
            e2.future = null;
          }
        }
      }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Debounced local consumer notifications keyed by 'key'.
   */
  private <T> void debouncedConsumer(String key,
                                     java.util.function.Supplier<T> payloadSupplier,
                                     java.util.function.Supplier<String> fingerprintSupplier,
                                     java.util.function.Consumer<T> dispatcher)
  {
    if (key == null || payloadSupplier == null || fingerprintSupplier == null || dispatcher == null)
    {
      return;
    }
    DebounceEntry entry = consumerDebounce.compute(key, (k, v) -> {
      if (v == null)
      {
        v = new DebounceEntry();
      }
      v.payloadSupplier = (java.util.function.Supplier<Object>) payloadSupplier;
      v.fingerprintSupplier = fingerprintSupplier;
      return v;
    });
    if (entry.future == null || entry.future.isDone())
    {
      int delay = 300 + eventRng.nextInt(401);
      entry.scheduledSeq = globalPublishSeq.incrementAndGet();
      entry.future = eventScheduler.schedule(() -> {
        try
        {
          DebounceEntry e = consumerDebounce.get(key);
          if (e == null)
          {
            return;
          }
          long seq = e.scheduledSeq;
          Long lastSeq = lastPublishedSeqByAddress.get(key);
          if (lastSeq != null && seq < lastSeq)
          {
            return; // stale
          }
          T payload = null;
          String fp = null;
          try
          {
            payload = (T) e.payloadSupplier.get();
            fp = e.fingerprintSupplier.get();
          }
          catch (Throwable ignored)
          {
          }
          if (fp == null)
          {
            fp = String.valueOf(System.identityHashCode(payload));
          }
          String last = consumerLastFp.get(key);
          if (!Objects.equals(last, fp))
          {
            try
            {
              dispatcher.accept(payload);
            }
            catch (Throwable ignored)
            {
            }
            consumerLastFp.put(key, fp);
            lastPublishedSeqByAddress.put(key, seq);
          }
          else
          {
            lastPublishedSeqByAddress.put(key, seq);
          }
        }
        finally
        {
          DebounceEntry e2 = consumerDebounce.get(key);
          if (e2 != null)
          {
            e2.future = null;
          }
        }
      }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Publishes the sender snapshot to its Vert.x event topic named by position: sender-{index}-tasks using debounced strategy.
   */
  private void publishSenderSummary(Integer port)
  {
    try
    {
      int idx = indexForPort(port);
      if (idx <= 0)
      {
        return; // not registered
      }
      TimedComPortSender s = senders.get(port);
      if (s == null)
      {
        return;
      }
      String address = String.format(senderPublishPattern, idx);
      if (publishingEnabled)
      {
        // Compute once to keep payload and fingerprint consistent
        TimedComPortSender ss = senders.get(port);
        final SenderSnapshot snap = ss == null ? null : ss.snapshot(25, 100);
        final String fp = fingerprintSenderSnapshot(snap);
        debouncedPublish(address,
            () -> snap,
            () -> fp
        );
      }
    }
    catch (Throwable t)
    {
      // Swallow publishing errors to not break core flow
    }
  }

  /**
   * Remove a sender by port and compact the registry order (shift indices left).
   * Also emits updated snapshots for all remaining senders to reflect new indices.
   */
  public void removeSender(Integer comPort)
  {
    if (comPort == null)
    {
      return;
    }
    try
    {
      TimedComPortSender s = senders.remove(comPort);
      attached.remove(comPort);
      orderedPorts.remove(comPort);
      // Close the sender and disconnect the underlying connection
      if (s != null)
      {
        try
        {
          s.close();
        }
        catch (Throwable ignored)
        {
        }
      }
      // Remove from global registry map and disconnect the connection if present
      try
      {
        ComPortConnection.TIMED_SENDERS.remove(comPort);
      }
      catch (Throwable ignored)
      {
      }
      try
      {
        ComPortConnection<?> conn = ComPortConnection.PORT_CONNECTIONS.get(comPort);
        if (conn != null)
        {
          try
          {
            conn.disconnect();
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
    finally
    {
      // Re-publish snapshots for all current senders with new indices
      for (Integer p : orderedPorts)
      {
        publishSenderSummary(p);
      }
      scheduleAggregatePublish();
    }
  }

  /**
   * Fetch the publisher and publish the message
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  protected void publishToAddress(String address, Object payload)
  {
    try
    {
      Key<VertxEventPublisher> key = Key.get(VertxEventPublisher.class, Names.named(address));
      var publisher = IGuiceContext.get(key);
      publisher.publish(payload);
      log.debug("✅ Published payload to '{}'", address);
    }
    catch (ConfigurationException t)
    {
      log.warn("🔥 Statistics publisher not registered - {}", address);
    }
    catch (Throwable t)
    {
      log.warn("🔥 Failed to publish to '{}': {}", address, t.getMessage(), t);
    }
  }

  private void emitStatus(Integer port, TimedComPortSender.State state, String msg)
  {
    ManagerStatus ms = new ManagerStatus(port, state, msg);
    for (MultiEmitter<? super ManagerStatus> e : statusEmitters)
    {
      try
      {
        e.emit(ms);
      }
      catch (Throwable ignored)
      {
      }
    }
  }

  private void emitProgress(Integer port, MessageProgress mp)
  {
    ManagerMessageProgress mmp = new ManagerMessageProgress(port, mp);
    for (MultiEmitter<? super ManagerMessageProgress> e : progressEmitters)
    {
      try
      {
        e.emit(mmp);
      }
      catch (Throwable ignored)
      {
      }
    }
  }

  /**
   * Return the currently managed senders (read-only snapshot).
   */
  public Map<Integer, TimedComPortSender> getSenders()
  {
    return Collections.unmodifiableMap(new LinkedHashMap<>(senders));
  }

  /**
   * Explicitly add/register a sender for the given COM port (or return the existing one).
   * This is a convenience wrapper over getOrCreateSender and will attach streams, maintain
   * insertion order for publishing, and immediately publish a first snapshot if newly added.
   */
  public TimedComPortSender addSender(Integer comPort)
  {
    return getOrCreateSender(comPort, null);
  }

  /**
   * Explicitly add/register a sender for the given COM port using the provided base config
   * (or return the existing one). See addSender(Integer) for behavior details.
   */
  public TimedComPortSender addSender(Integer comPort, Config baseConfig)
  {
    return getOrCreateSender(comPort, baseConfig);
  }

  /**
   * Mark the message with the given id as completed (success) on its active sender, if currently sending.
   * Returns true if a matching active message was found and completed; false otherwise.
   * Note: This method does not require the caller to know the COM port; the manager locates it.
   */
  public boolean markCompleted(String messageId)
  {
    return markCompletedReturningSpec(messageId).isPresent();
  }

  /**
   * Mark the message with the given id as completed and return the original MessageSpec if present.
   */
  public java.util.Optional<MessageSpec> markCompletedReturningSpec(String messageId)
  {
    if (messageId == null || messageId.isBlank())
    {
      return java.util.Optional.empty();
    }
    // First try fast path via id->port mapping
    Integer port = messageIdToPort.get(messageId);
    if (port != null)
    {
      TimedComPortSender s = senders.get(port);
      if (s != null)
      {
        SenderSnapshot snap = s.snapshot(0, 0);
        if (snap != null && snap.getSending() != null && messageId.equals(snap.getSending()
                                                                              .getId()))
        {
          MessageSpec spec = s.getActiveMessageSpec();
          s.complete();
          return java.util.Optional.ofNullable(spec);
        }
      }
    }
    // Fallback: scan all senders to find a currently sending message with this id
    for (Map.Entry<Integer, TimedComPortSender> e : senders.entrySet())
    {
      TimedComPortSender s = e.getValue();
      if (s == null)
      {
        continue;
      }
      SenderSnapshot snap = s.snapshot(0, 0);
      if (snap != null && snap.getSending() != null && messageId.equals(snap.getSending()
                                                                            .getId()))
      {
        // cache mapping for future fast path
        messageIdToPort.put(messageId, e.getKey());
        MessageSpec spec = s.getActiveMessageSpec();
        s.complete();
        return java.util.Optional.ofNullable(spec);
      }
    }
    return java.util.Optional.empty();
  }

  /**
   * Convenience: mark the currently active message on the specified COM port as Completed.
   */
  public boolean markCompleted(int comPort)
  {
    return markCompletedReturningSpec(comPort).isPresent();
  }

  /**
   * Mark the current message on the specified port as Completed and return the original MessageSpec if present.
   */
  public java.util.Optional<MessageSpec> markCompletedReturningSpec(int comPort)
  {
    TimedComPortSender s = senders.get(comPort);
    if (s == null)
    {
      return java.util.Optional.empty();
    }
    // Prefer the authoritative active spec if present
    MessageSpec active = s.getActiveMessageSpec();
    if (active != null)
    {
      s.complete();
      return java.util.Optional.of(active);
    }
    // Fallback to snapshot if available
    SenderSnapshot snap = s.snapshot(0, 0);
    if (snap != null && snap.sending != null)
    {
      s.complete();
      MessageStat ms = snap.sending;
      MessageSpec reconstructed = new MessageSpec(ms.getId(), ms.getTitle(), ms.getPayload(), ms.getEffectiveConfig());
      return java.util.Optional.of(reconstructed);
    }
    return java.util.Optional.empty();
  }

  /**
   * Convenience: mark the currently active message on the specified COM port as Errored (terminal).
   */
  public boolean markErrored(int comPort, String reason)
  {
    return markErroredReturningSpec(comPort, reason).isPresent();
  }

  /**
   * Mark the current message on the specified port as Errored and return the original MessageSpec if present.
   */
  public java.util.Optional<MessageSpec> markErroredReturningSpec(int comPort, String reason)
  {
    TimedComPortSender s = senders.get(comPort);
    if (s == null)
    {
      return java.util.Optional.empty();
    }
    // Briefly tolerate race by polling for active/snapshot sending for a short window
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
    while (true)
    {
      // Prefer the authoritative active spec if present
      MessageSpec active = s.getActiveMessageSpec();
      if (active != null)
      {
        s.error(reason);
        return java.util.Optional.of(active);
      }
      // Fallback to snapshot if available
      SenderSnapshot snap = s.snapshot(0, 0);
      if (snap != null && snap.sending != null)
      {
        s.error(reason);
        MessageStat ms = snap.sending;
        MessageSpec reconstructed = new MessageSpec(ms.getId(), ms.getTitle(), ms.getPayload(), ms.getEffectiveConfig());
        return java.util.Optional.of(reconstructed);
      }
      if (System.nanoTime() >= deadline)
      {
        break;
      }
      try { Thread.sleep(1); } catch (InterruptedException ignored) { }
    }
    return java.util.Optional.empty();
  }

  /**
   * Convenience overload: mark as Errored with a default message.
   */
  public boolean markErrored(int comPort)
  {
    return markErroredReturningSpec(comPort, "Externally errored").isPresent();
  }

  /**
   * Mark the message with the given id as Errored and return the original MessageSpec if present.
   */
  public java.util.Optional<MessageSpec> markErroredReturningSpec(String messageId, String reason)
  {
    if (messageId == null || messageId.isBlank())
    {
      return java.util.Optional.empty();
    }
    // Fast path via id->port mapping
    Integer port = messageIdToPort.get(messageId);
    if (port != null)
    {
      TimedComPortSender s = senders.get(port);
      if (s != null)
      {
        SenderSnapshot snap = s.snapshot(0, 0);
        if (snap != null && snap.getSending() != null && messageId.equals(snap.getSending()
                                                                              .getId()))
        {
          MessageSpec spec = s.getActiveMessageSpec();
          s.error(reason);
          return java.util.Optional.ofNullable(spec);
        }
      }
    }
    // Fallback: scan all senders
    for (Map.Entry<Integer, TimedComPortSender> e : senders.entrySet())
    {
      TimedComPortSender s = e.getValue();
      if (s == null)
      {
        continue;
      }
      SenderSnapshot snap = s.snapshot(0, 0);
      if (snap != null && snap.getSending() != null && messageId.equals(snap.getSending()
                                                                            .getId()))
      {
        messageIdToPort.put(messageId, e.getKey());
        MessageSpec spec = s.getActiveMessageSpec();
        s.error(reason);
        return java.util.Optional.ofNullable(spec);
      }
    }
    return java.util.Optional.empty();
  }

  /**
   * Convenience: mark the message with the given id as Errored (defaults a reason).
   */
  public boolean markErrored(String messageId)
  {
    return markErrored(messageId, "Externally errored");
  }

  /**
   * Convenience: mark the message with the given id as Errored using the provided reason.
   */
  public boolean markErrored(String messageId, String reason)
  {
    return markErroredReturningSpec(messageId, reason).isPresent();
  }
}
