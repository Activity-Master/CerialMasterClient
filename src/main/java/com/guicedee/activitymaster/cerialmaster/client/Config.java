package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@NgDataType
@Getter
@Setter
@Accessors(chain = true)
public class Config implements INgDataType<Config>
{
		private int assignedRetry = 3;
		public long assignedDelayMs = 2800;
		public long assignedTimeoutMs = 3000;
		/**
			* When true, the sender will always wait the full timeout after a send, even if externally marked completed earlier.
			*/
		public boolean alwaysWaitFullTimeoutAfterSend = false;
		/**
		 * When true, the task will be treated as successful once the total wait of
		 * (assignedRetry x assignedDelayMs) + assignedTimeoutMs has elapsed,
		 * even if no success was otherwise signaled.
		 * Note: default is false to avoid impacting existing logic.
		 */
		public boolean alwaysSucceed = false;
		
		public Config()
		{
		}
		
		public Config(int assignedRetry, long assignedDelayMs, long assignedTimeoutMs)
		{
				this(assignedRetry, assignedDelayMs, assignedTimeoutMs, false);
		}
		
		public Config(int assignedRetry, long assignedDelayMs, long assignedTimeoutMs, boolean alwaysWaitFullTimeoutAfterSend)
		{
				if (assignedRetry < 0)
				{
						throw new IllegalArgumentException("assignedRetry must be >= 0");
				}
				if (assignedDelayMs < 0)
				{
						throw new IllegalArgumentException("assignedDelayMs must be >= 0");
				}
				if (assignedTimeoutMs < 0)
				{
						throw new IllegalArgumentException("assignedTimeoutMs must be >= 0");
				}
				this.assignedRetry = assignedRetry;
				this.assignedDelayMs = assignedDelayMs;
				this.assignedTimeoutMs = assignedTimeoutMs;
				this.alwaysWaitFullTimeoutAfterSend = alwaysWaitFullTimeoutAfterSend;
		}
}
