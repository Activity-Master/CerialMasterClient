package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.client.services.IDefaultService;

import java.io.*;

public interface ITerminalReceiveMessage<J extends ITerminalReceiveMessage<J>>
		extends Serializable, IDefaultService<J>
{
	void receiveTerminalMessage(String message, Throwable exception, ComPortConnection<?> comPortConnection);
}
