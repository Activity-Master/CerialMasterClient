package com.guicedee.activitymaster.cerialmaster.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * Strongly-typed, transport-neutral representation of a serial (COM) port connection as it is stored
 * within ActivityMaster (as a {@code SerialConnectionPort} resource item with classifications).
 *
 * <p>This is the cerial-module counterpart of the geography module's {@code GeographyCountry} DTO: it
 * is the canonical read shape returned by both the GraphQL data fetcher and the REST resource so the
 * same warehouse-backed data is available over either transport.</p>
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CerialComPort implements Serializable
{
	@Serial
	private static final long serialVersionUID = 1L;

	/** The warehouse {@code ResourceItem} row identifier backing this COM port. */
	private UUID resourceItemId;
	/** The COM port number (e.g. {@code 3} for COM3). */
	private Integer comPort;
	/** The device type of the port (e.g. {@code Device}, {@code Server}, {@code Scanner}). */
	private String deviceType;
	/** The last registered status of the port (e.g. {@code Open}, {@code Silent}). */
	private String status;
	/** The configured baud rate. */
	private Integer baudRate;
	/** The configured read/write buffer size. */
	private Integer bufferSize;
	/** The number of data bits per byte. */
	private Integer dataBits;
	/** The number of stop bits. */
	private Integer stopBits;
	/** The parity setting (numeric form). */
	private Integer parity;
}

