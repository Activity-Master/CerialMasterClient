package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.ComPortConnection;
import com.guicedee.guicedinjection.interfaces.IDefaultService;

import java.io.Serializable;

public interface IErrorReceiveMessage<J extends IErrorReceiveMessage<J>>
		extends Serializable, IDefaultService<J>
{
	void receiveErrorMessage(String message, Throwable exception, ComPortConnection<?> comPortConnection);
}
