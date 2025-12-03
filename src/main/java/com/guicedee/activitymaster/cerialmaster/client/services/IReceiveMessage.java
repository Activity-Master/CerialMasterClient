package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.client.services.IDefaultService;

import java.io.*;

public interface IReceiveMessage<J extends IReceiveMessage<J>>
		extends Serializable, IDefaultService<J>
{
	void receiveMessage(String message, ComPortConnection<?> comPortConnection);
}
