package com.guicedee.activitymaster.cerialmaster.client;

import com.jwebmp.core.base.angular.client.annotations.angular.NgDataType;
import com.jwebmp.core.base.angular.client.services.interfaces.INgDataType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
@NgDataType
public class MessageSpec<J extends MessageSpec<J>> implements INgDataType<J>
{
  private String id;
  private String title;
  private String friendlyName;
  private String payload;
  private Config config; // per-message
  /** Optional supplier to (re)generate the payload at first attempt and on each retry. */
  private java.util.function.Supplier<String> messageSupplier;
  /** Optional attempt-aware supplier: receives current attempt number (1-based). If set, takes precedence over messageSupplier. */
  private java.util.function.IntFunction<String> messageByAttemptSupplier;
  /**
   * If true, this message will be treated as successfully completed at the end of its allocated timeout window,
   * regardless of actual send/response outcome. All retries, delays, and timeout waiting still apply.
   */
  public boolean alwaysSucceed = false;

  public MessageSpec(String id, String payload, Config config)
  {
    this.id = id;
    this.title = id;
    this.friendlyName = id;
    this.payload = payload;
    this.config = config;
  }

  public MessageSpec(String id, String title, String payload, Config config)
  {
    this.id = id;
    this.title = title == null ? id : title;
    this.friendlyName = this.title;
    this.payload = payload;
    this.config = config;
  }

  public MessageSpec(String id, String title, String friendlyName, String payload, Config config)
  {
    this.id = id;
    this.title = title == null ? id : title;
    this.friendlyName = (friendlyName == null || friendlyName.isBlank()) ? this.title : friendlyName;
    this.payload = payload;
    this.config = config;
  }

  /**
   * Ensure this message has a non-null/non-blank id. If absent, a UUID is generated and title defaults to id when blank.
   * Also ensures friendlyName defaults sensibly.
   * Returns the resulting id.
   */
  public String generateId()
  {
    if (id == null || id.isBlank())
    {
      id = java.util.UUID.randomUUID().toString();
    }
    if (title == null || title.isBlank())
    {
      title = id;
    }
    if (friendlyName == null || friendlyName.isBlank())
    {
      friendlyName = title;
    }
    return id;
  }

  /**
   * Produce the message content to send.
   * If a supplier is provided, it will be invoked on every call (first try and retries)
   * to refresh the payload. If the supplier returns null, the previous payload is retained.
   * Falls back to title if payload is blank; otherwise empty string.
   */
  /**
   * Produce the message content to send for a specific attempt.
   * attempt is 1-based when called from TimedComPortSender.
   */
  public String generateMessage(int attempt)
  {
    // First, use attempt-aware supplier if provided
    try
    {
      if (messageByAttemptSupplier != null)
      {
        String supplied = messageByAttemptSupplier.apply(attempt);
        if (supplied != null)
        {
          this.payload = supplied;
        }
      }
      else if (messageSupplier != null)
      {
        String supplied = messageSupplier.get();
        if (supplied != null)
        {
          this.payload = supplied;
        }
      }
    }
    catch (Throwable ignored)
    {
    }

    if (payload != null && !payload.isBlank())
    {
      return payload;
    }
    // When payload is blank after trimming, do not send any message content.
    // This allows the retry logic to continue without transmitting fallback values like title or id.
    return "";
  }

  /**
   * Backward-compatible no-arg variant; uses attempt=0.
   */
  public String generateMessage()
  {
    return generateMessage(0);
  }

  public J setAlwaysSucceed(boolean alwaysSucceed)
  {
    this.alwaysSucceed = alwaysSucceed;
    return (J)this;
  }

  /**
   * Attach a supplier that can dynamically generate payloads on first try and on every retry.
   * Returns this for chaining.
   */
  public J withSupplier(java.util.function.Supplier<String> supplier)
  {
    this.messageSupplier = supplier;
    return (J)this;
  }

  /**
   * Attach an attempt-aware supplier. The attempt parameter is 1-based during retries/first attempt.
   * This takes precedence over the no-arg supplier if both are set.
   */
  public J withSupplier(java.util.function.IntFunction<String> supplier)
  {
    this.messageByAttemptSupplier = supplier;
    return (J)this;
  }

  /**
   * Getter override to ensure friendlyName() always resolves from stored value,
   * falling back to title, then id, then empty string, without mutating state.
   */
  public String getFriendlyName()
  {
    if (friendlyName != null && !friendlyName.isBlank())
    {
      return friendlyName;
    }
    if (title != null && !title.isBlank())
    {
      return title;
    }
    if (id != null && !id.isBlank())
    {
      return id;
    }
    return "";
  }
}
