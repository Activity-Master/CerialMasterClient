# Dependency and Integration Map

```mermaid
graph TD
  Pom[pom.xml\ncerial-client module]
  ActivityMaster[Activity Master FSDM workflows]
  TimedSender[TimedComPortSender\nper-port retry engine]
  MultiSender[MultiTimedComPortSender\nmulti-port manager]
  Connection[ComPortConnection registry\nport-scoped cache]
  Services[GuicedEE Service Hooks\nSPI listeners]
  Vertx[Vert.x Event Bus publisher]
  Mutiny[Mutiny Uni and Multi\nreactive streams]
  Typescript[JWebMP TypeScript Client\ngenerated DTO metadata]
  Serial[com.guicedee.cerial drivers\nhardware IO]
  Logging[Log4j2 appenders\nstructured telemetry]
  Tests[Java Micro Harness + Jacoco]

  ActivityMaster -->|enqueue/pause/cancel| TimedSender
  TimedSender --> Mutiny
  TimedSender --> Vertx
  TimedSender --> Connection
  MultiSender --> Connection
  Connection --> Serial
  Connection --> Services
  TimedSender --> Typescript
  TimedSender --> Logging
  Tests --> TimedSender
  Pom --> TimedSender
```

Trust boundaries: the ActivityMaster caller boundary feeds the library; SPI listeners and serial drivers are external and must be validated before participation; telemetry and TypeScript emission leave the boundary and rely on Log4j2 plus Mutiny backpressure to avoid untrusted input amplification.
