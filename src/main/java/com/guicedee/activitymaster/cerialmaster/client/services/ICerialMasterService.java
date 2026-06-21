package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.ComPortConnection;
import com.guicedee.activitymaster.cerialmaster.client.dto.CerialComPort;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.resourceitem.IResourceItemType;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import java.util.List;


public interface ICerialMasterService<J extends ICerialMasterService<J>> {
    String CerialMasterSystemName = "Cerial Master System";

    Uni<ComPortConnection<?>> addOrUpdateConnection(Mutiny.Session session, ComPortConnection<?> comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

    Uni<ComPortConnection<?>> updateStatus(Mutiny.Session session, ComPortConnection<?> comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

    Uni<ComPortConnection<?>> findComPortConnection(Mutiny.Session session, ComPortConnection<?> comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

//	ComPortConnection<?> registerNewConnection(ComPortConnection<?> comPortConnection);

    Uni<ComPortConnection<?>> getComPortConnection(Mutiny.Session session, Integer comPort, IEnterprise<?, ?> enterprise);

    Uni<ComPortConnection<?>> getComPortConnectionDirect(Integer comPort);

    /**
     * Optional timed sender configuration which, if provided, will be used to create/register a TimedComPortSender
     * against the retrieved COM port connection.
     */
    Uni<ComPortConnection<?>> getComPortConnection(Mutiny.Session session, Integer comPort, IEnterprise<?, ?> enterprise, com.guicedee.activitymaster.cerialmaster.client.Config timedConfig);

    Uni<ComPortConnection<?>> getScannerPortConnection(Mutiny.Session session, Integer comPort, IEnterprise<?, ?> enterprise);

    Uni<ComPortConnection<?>> getScannerPortConnection(Mutiny.Session session, Integer comPort, IEnterprise<?, ?> enterprise, com.guicedee.activitymaster.cerialmaster.client.Config timedConfig);

    Uni<List<String>> listComPorts();

    Uni<List<String>> listRegisteredComPorts(Mutiny.Session session, IEnterprise<?, ?> enterprise);

    Uni<List<String>> listAvailableComPorts(Mutiny.Session session, IEnterprise<?, ?> enterprise);

    /**
     * Resolves a single registered COM port by its number and hydrates a fully-populated
     * {@link CerialComPort} DTO by reading the persisted {@code SerialConnectionPort} resource item and
     * its supporting classifications (device type, status, baud rate, buffer size, data/stop bits, parity).
     *
     * <p>This is the canonical read path used by both the GraphQL data fetcher and the REST resource so
     * the strongly-typed DTO returned by either transport reflects exactly what is stored in ActivityMaster.</p>
     *
     * @param session       the active reactive session
     * @param comPort       the COM port number (e.g. {@code 3})
     * @param system        the requesting system (security scope)
     * @param identityToken optional security identity token(s)
     * @return a {@link Uni} emitting the hydrated {@link CerialComPort}
     */
    Uni<CerialComPort> findComPortDetailed(Mutiny.Session session, Integer comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

    /**
     * Lists every registered COM port as a fully-hydrated {@link CerialComPort} DTO read straight from
     * the ActivityMaster warehouse.
     *
     * @param session       the active reactive session
     * @param system        the requesting system (security scope)
     * @param identityToken optional security identity token(s)
     * @return a {@link Uni} emitting the list of hydrated COM ports
     */
    Uni<List<CerialComPort>> listComPortsDetailed(Mutiny.Session session, ISystems<?, ?> system, java.util.UUID... identityToken);

    /**
     * Creates or updates a COM port from a strongly-typed {@link CerialComPort} DTO and returns the
     * fully-hydrated DTO read back from the warehouse.
     *
     * <p>This is the DTO-facing write counterpart to {@link #findComPortDetailed} used by the REST
     * resource. It maps the DTO onto a {@link ComPortConnection} and delegates to
     * {@link #addOrUpdateConnection(Mutiny.Session, ComPortConnection, ISystems, java.util.UUID...)}
     * (a genuine upsert), so a single code path persists the {@code SerialConnectionPort} resource item
     * and its supporting classifications (COM port number, device type, status, baud rate, buffer size,
     * data/stop bits, parity) regardless of transport. The port is keyed by its {@code comPort} number.</p>
     *
     * @param session       the active reactive session
     * @param comPort       the COM port DTO to create or update (its {@code comPort} number is required)
     * @param system        the requesting system (security scope)
     * @param identityToken optional security identity token(s)
     * @return a {@link Uni} emitting the hydrated, persisted {@link CerialComPort}
     */
    Uni<CerialComPort> addOrUpdateComPortDetailed(Mutiny.Session session, CerialComPort comPort, ISystems<?, ?> system, java.util.UUID... identityToken);
}
