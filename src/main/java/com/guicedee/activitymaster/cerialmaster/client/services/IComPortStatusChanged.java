package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.ComPortConnection;
import com.guicedee.cerial.enumerations.ComPortStatus;
import com.guicedee.client.services.IDefaultService;

import java.io.Serializable;

public interface IComPortStatusChanged<J extends IComPortStatusChanged<J>>
		extends Serializable, IDefaultService<J>
{
	void onComPortStatusChanged(ComPortConnection<?> comPortConnection, ComPortStatus oldStatus, ComPortStatus newStatus);
}
