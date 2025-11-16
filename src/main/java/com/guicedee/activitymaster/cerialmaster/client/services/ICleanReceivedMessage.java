package com.guicedee.activitymaster.cerialmaster.client.services;

import com.guicedee.activitymaster.cerialmaster.client.*;
import com.guicedee.guicedinjection.interfaces.*;

import java.io.*;

public interface ICleanReceivedMessage<J extends ICleanReceivedMessage<J>>
		extends Serializable, IDefaultService<J>
{
	String cleanMessage(String message, ComPortConnection<?> comPortConnection);
}
