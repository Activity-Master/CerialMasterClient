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

    Uni<ComPortConnection<?>> addOrUpdateConnection(Mutiny.StatelessSession session, ComPortConnection<?> comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

    Uni<ComPortConnection<?>> updateStatus(Mutiny.StatelessSession session, ComPortConnection<?> comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

    Uni<ComPortConnection<?>> findComPortConnection(Mutiny.StatelessSession session, ComPortConnection<?> comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

    Uni<ComPortConnection<?>> getComPortConnection(Mutiny.StatelessSession session, Integer comPort, IEnterprise<?, ?> enterprise);

    Uni<ComPortConnection<?>> getComPortConnectionDirect(Integer comPort);

    Uni<ComPortConnection<?>> getComPortConnection(Mutiny.StatelessSession session, Integer comPort, IEnterprise<?, ?> enterprise, com.guicedee.activitymaster.cerialmaster.client.Config timedConfig);

    Uni<ComPortConnection<?>> getScannerPortConnection(Mutiny.StatelessSession session, Integer comPort, IEnterprise<?, ?> enterprise);

    Uni<ComPortConnection<?>> getScannerPortConnection(Mutiny.StatelessSession session, Integer comPort, IEnterprise<?, ?> enterprise, com.guicedee.activitymaster.cerialmaster.client.Config timedConfig);

    Uni<List<String>> listComPorts();

    Uni<List<String>> listRegisteredComPorts(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise);

    Uni<List<String>> listAvailableComPorts(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise);

    Uni<CerialComPort> findComPortDetailed(Mutiny.StatelessSession session, Integer comPort, ISystems<?, ?> system, java.util.UUID... identityToken);

    Uni<List<CerialComPort>> listComPortsDetailed(Mutiny.StatelessSession session, ISystems<?, ?> system, java.util.UUID... identityToken);

    Uni<CerialComPort> addOrUpdateComPortDetailed(Mutiny.StatelessSession session, CerialComPort comPort, ISystems<?, ?> system, java.util.UUID... identityToken);
}
