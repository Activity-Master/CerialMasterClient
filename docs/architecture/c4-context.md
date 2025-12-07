```mermaid
graph TD
  subgraph ActivityMasterBoundary [Activity Master Domain]
    Fsdm[Activity Master FSDM workflows]
  end

  subgraph CerialClientBoundary [Cerial Master Client Library]
    Client[Cerial Master Client\nJava 25 COM messaging orchestrator]
    Injection[GuicedEE Injection\nService loader and module wiring]
    Telemetry[Mutiny Runtime\nMulti and Uni status streams]
  end

  subgraph ExternalBoundary [External Integrations]
    SerialDrivers[Serial Hardware Drivers\ncom.guicedee.cerial]
    Logging[Log4j2 Observability]
    Typescript[JWebMP TypeScript Bridge\nDTO metadata emission]
  end

  Fsdm -->|invoke send, pause, cancel APIs| Client
  Client -->|load services| Injection
  Client -->|publish status and snapshots| Telemetry
  Client -->|log retries and errors| Logging
  Client -->|write bytes| SerialDrivers
  Client -->|emit DTO schema| Typescript
  SerialDrivers -->|status callbacks| Client
```

Trust boundaries: ActivityMaster workflows call into the client library boundary; serial hardware and TypeScript generation sit outside the library boundary and require validation and logging. Threat highlights include malformed COM payloads from hardware drivers and misconfigured service-loader modules; mitigate with Log4j2 audit trails, GuicedEE module checks, and Mutiny stream backpressure.
