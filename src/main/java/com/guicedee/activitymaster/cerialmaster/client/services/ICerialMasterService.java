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
}
