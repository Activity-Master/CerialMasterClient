```mermaid
erDiagram
title Messaging Domain ERD
MESSAGE_SPEC {
  string id PK
  string payload
  integer priority
  string status
}
CONFIG {
  integer assignedRetry
  integer assignedDelayMs
  integer assignedTimeoutMs
}
MESSAGE_RESULT {
  string messageId FK
  string state
  integer attempts
}
MESSAGE_STAT {
  string messageId FK
  integer attemptCount
  timestamp startEpoch
  timestamp endEpoch
}
SENDER_SNAPSHOT {
  integer lastGroupCompleted
  integer lastGroupPlanned
}
GROUP_RESULT {
  string groupId PK
  integer completedCount
}
MESSAGE_SPEC ||--|| CONFIG : "applies"
MESSAGE_RESULT }|..|{ MESSAGE_STAT : "records"
MESSAGE_RESULT ||--|{ GROUP_RESULT : "part of"
MESSAGE_STAT }|..|{ SENDER_SNAPSHOT : "feeds"
