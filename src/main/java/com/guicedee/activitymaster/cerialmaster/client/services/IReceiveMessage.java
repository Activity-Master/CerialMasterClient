package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.guicedinjection.interfaces.*;

import java.io.*;

public interface IReceiveMessage<J extends IReceiveMessage<J>>
		extends Serializable, IDefaultService<J>
{
	void receiveMessage(String message, ComPortConnection<?> comPortConnection);
}
