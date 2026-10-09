-- Spring Modulith Event Publication Registry (Outbox). Modulith 2.x JPA 엔티티 기준.
CREATE TABLE event_publication (
    id                     BINARY(16)    NOT NULL,
    listener_id            VARCHAR(512)  NOT NULL,
    event_type             VARCHAR(512)  NOT NULL,
    serialized_event       VARCHAR(4000) NOT NULL,
    publication_date       DATETIME(6)   NOT NULL,
    completion_date        DATETIME(6),
    status                 ENUM ('COMPLETED', 'FAILED', 'PROCESSING', 'PUBLISHED', 'RESUBMITTED'),
    completion_attempts    INT           NOT NULL,
    last_resubmission_date DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_event_publication_completion_date (completion_date)
) ENGINE = InnoDB;
