package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.ComPortConnection;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.resourceitem.IResourceItemType;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

import java.util.List;


public interface ICerialMasterService<J extends ICerialMasterService<J>>
{
	String CerialMasterSystemName = "Cerial Master System";

	Uni<IResourceItemType<?,?>> getSerialConnectionType(Mutiny.Session session, ISystems<?,?> system, java.util.UUID... identityToken);

	Uni<ComPortConnection<?>> addOrUpdateConnection(Mutiny.Session session, ComPortConnection<?> comPort, ISystems<?,?> system, java.util.UUID... identityToken);

	Uni<ComPortConnection<?>> updateStatus(Mutiny.Session session, ComPortConnection<?> comPort, ISystems<?,?> system, java.util.UUID... identityToken);

	Uni<ComPortConnection<?>> findComPortConnection(Mutiny.Session session, ComPortConnection<?> comPort, ISystems<?,?> system, java.util.UUID... identityToken);

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
}
