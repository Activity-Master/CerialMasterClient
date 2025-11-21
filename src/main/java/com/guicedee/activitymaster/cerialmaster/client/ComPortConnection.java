package com.guicedee.activitymaster.cerialmaster.client;

import com.fasterxml.jackson.annotation.*;
import com.guicedee.activitymaster.cerialmaster.client.services.IReceiveMessage;
import com.guicedee.activitymaster.fsdm.client.services.IResourceItemService;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.resourceitem.IResourceItem;
import com.guicedee.activitymaster.cerialmaster.client.services.IComPortStatusChanged;
import com.guicedee.cerial.CerialPortConnection;
import com.guicedee.cerial.enumerations.BaudRate;
import com.guicedee.cerial.enumerations.ComPortType;
import com.guicedee.cerial.enumerations.ComPortStatus;
import com.guicedee.guicedinjection.GuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.*;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true, value = {"inspection"})
@JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE, setterVisibility = NONE)
public class ComPortConnection<J extends ComPortConnection<J>>
		extends CerialPortConnection<J>
		implements Serializable, Comparable<J>
{
	private static ComPortStatus coalesceStatus(ComPortStatus status)
	{
		if (status == null)
		{
			return null;
		}
		// Merge Idle into Silent to avoid conflicting semantics across the app.
		// Some builds may not declare ComPortStatus.Idle; compare by name to avoid compile-time dependency.
		return ("Idle".equals(status.name())) ? ComPortStatus.Silent : status;
	}
	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Public registry of COM port number to connection instance.
	 * Always use the instance from this map to access a given port, regardless of status.
	 */
	@JsonIgnore
	public static final Map<Integer, ComPortConnection<?>> PORT_CONNECTIONS = new ConcurrentHashMap<>();

	/**
	 * Public registry of timed senders per COM port.
	 */
	@JsonIgnore
	public static final Map<Integer, TimedComPortSender> TIMED_SENDERS = new ConcurrentHashMap<>();

	private UUID id;

	@JsonIgnore
	private static final Set<IReceiveMessage<?>> receivers = new LinkedHashSet<>();

	@JsonIgnore
	private static final Set<IComPortStatusChanged<?>> statusListeners = new LinkedHashSet<>();

	@JsonIgnore
	private final java.util.Set<java.util.function.BiConsumer<CerialPortConnection<?>, ComPortStatus>> statusUpdateCallbacks =
			new java.util.concurrent.CopyOnWriteArraySet<>();

	@JsonIgnore
	private IResourceItem<?, ?> resourceItem;

	public ComPortConnection()
	{

	}

	public ComPortConnection(Integer comPort, ComPortType type)
	{
		super(comPort, BaudRate.$9600);
		setComPortType(type);
		if (comPort != null)
		{
			this.setComPort(comPort);
			// Registration into PORT_CONNECTIONS is handled by getOrCreate() to avoid recursive updates during compute.
		}
		else
		{
			throw new RuntimeException("Cannot create a com port connection with a null com port");
		}
	}

	/**
	 * Retrieves the canonical ComPortConnection for the given port, creating it if necessary.
	 * The returned instance is always the one stored in PORT_CONNECTIONS.
	 */
	public static ComPortConnection<?> getOrCreate(Integer comPort, ComPortType type)
	{
		if (comPort == null)
		{
			throw new RuntimeException("Cannot get or create a com port connection with a null com port");
		}
		ComPortConnection<?> existing = PORT_CONNECTIONS.computeIfAbsent(comPort, k -> new ComPortConnection<>(comPort, type));
		if (existing != null && type != null)
		{
			existing.setComPortType(type);
		}
		return existing;
	}

	public @org.jspecify.annotations.NonNull J setResourceItem(IResourceItem<?, ?> item)
	{
		this.resourceItem = item;
		setId(item.getId());
		//noinspection unchecked
		return (J) this;
	}

	public Uni<IResourceItem<?, ?>> getResourceItem(Mutiny.Session session)
	{
		if (resourceItem == null && id != null)
		{
			IResourceItemService<?> service = com.guicedee.client.IGuiceContext.get(IResourceItemService.class);
			return service.findByUUID(session, id);
		}
		else if (resourceItem != null)
			return Uni.createFrom().item(resourceItem);
		else
			return Uni.createFrom().failure(new RuntimeException("No resource item found, id not set"));
	}

	public Set<IReceiveMessage<?>> getReceivers()
	{
		if (receivers.isEmpty())
		{
			Set<IReceiveMessage> receiveMessages = GuiceContext.instance()
			                                                   .getLoader(IReceiveMessage.class, ServiceLoader.load(IReceiveMessage.class));
			for (IReceiveMessage receiveMessage : receiveMessages)
			{
				receivers.add(receiveMessage);
			}
		}
		return receivers;
	}

	private Set<IComPortStatusChanged<?>> getStatusListeners()
	{
		if (statusListeners.isEmpty())
		{
			Set<IComPortStatusChanged> loaded = GuiceContext.instance()
			                                             .getLoader(IComPortStatusChanged.class, ServiceLoader.load(IComPortStatusChanged.class));
			for (IComPortStatusChanged l : loaded)
			{
				statusListeners.add(l);
			}
		}
		return statusListeners;
	}

	@Override
	public int compareTo(J o)
	{
		return Integer.compare(getComPort(), o.getComPort());
	}

	public UUID getId()
	{
		return id;
	}

	public ComPortConnection<J> setId(UUID id)
	{
		this.id = id;
		return this;
	}

 @SuppressWarnings("unchecked")
 public @org.jspecify.annotations.NonNull J setComPortStatus(ComPortStatus status)
 {
 	ComPortStatus oldCoalesced = coalesceStatus(getComPortStatus());
 	ComPortStatus newCoalesced = coalesceStatus(status);
 	super.setComPortStatus(newCoalesced);
 	notifyStatusListeners(oldCoalesced, newCoalesced);
 	return (J) this;
 }

 @SuppressWarnings("unchecked")
 public @org.jspecify.annotations.NonNull J setComPortStatus(ComPortStatus status, boolean silent)
 {
 	ComPortStatus oldCoalesced = coalesceStatus(getComPortStatus());
 	ComPortStatus newCoalesced = coalesceStatus(status);
 	super.setComPortStatus(newCoalesced, silent);
 	if (!silent)
 	{
 		notifyStatusListeners(oldCoalesced, newCoalesced);
 	}
 	return (J) this;
 }

	private void notifyStatusListeners(ComPortStatus oldStatus, ComPortStatus newStatus)
	{
		if (Objects.equals(oldStatus, newStatus))
		{
			return;
		}
		for (IComPortStatusChanged<?> listener : getStatusListeners())
		{
			try
			{
				listener.onComPortStatusChanged(this, oldStatus, newStatus);
			}
			catch (Throwable t)
			{
				// best-effort: listeners must not break the flow
			}
		}
		// Fire local per-connection callbacks registered via the superclass-style API
		/*for (java.util.function.BiConsumer<CerialPortConnection<?>, ComPortStatus> cb : statusUpdateCallbacks)
		{
			try
			{
				cb.accept(this, newStatus);
			}
			catch (Throwable t)
			{
				// swallow listener exceptions
			}
		}*/
	}

	@Override
	public Integer sortOrder()
	{
		return getComPort();
	}

	@SuppressWarnings("unchecked")
	@Override
	public J onComPortStatusUpdate(java.util.function.BiConsumer<CerialPortConnection<?>, ComPortStatus> callback)
	{
		if (callback != null)
		{
			statusUpdateCallbacks.add(callback);
		}
		return (J) this;
	}

	@SuppressWarnings("unchecked")
	@Override
	public @org.jspecify.annotations.NonNull J setComPortStatusUpdate(java.util.function.BiConsumer<CerialPortConnection<?>, ComPortStatus> callback)
	{
		return onComPortStatusUpdate(callback);
	}

	/**
	 * Remove a previously registered local status update callback.
	 */
	public ComPortConnection<J> removeComPortStatusUpdate(java.util.function.BiConsumer<CerialPortConnection<?>, ComPortStatus> callback)
	{
		if (callback != null)
		{
			statusUpdateCallbacks.remove(callback);
		}
		return this;
	}

	/**
	 * Get or create a timed sender for this COM port using the provided configuration.
	 */
	public TimedComPortSender getOrCreateTimedSender(Config config)
	{
		TimedComPortSender existing = TIMED_SENDERS.get(getComPort());
		if (existing != null)
		{
			if (config != null)
				existing.updateConfig(config);
			return existing;
		}
		TimedComPortSender created = new TimedComPortSender(this, config);
		TIMED_SENDERS.put(getComPort(), created);
		return created;
	}

	/**
	 * Returns the timed sender for the given port if registered.
	 */
	public static TimedComPortSender getTimedSender(Integer comPort)
	{
		return TIMED_SENDERS.get(comPort);
	}
}
