CREATE TABLE IF NOT EXISTS service_counter (
    counter_id   SERIAL PRIMARY KEY,
    counter_name VARCHAR(100) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'FREE'
);

CREATE TABLE IF NOT EXISTS customer (
    customer_id   SERIAL PRIMARY KEY,
    customer_name VARCHAR(100) NOT NULL,
    phone         VARCHAR(20),
    queue_number  INT NOT NULL,
    counter_id    INT REFERENCES service_counter(counter_id) ON DELETE SET NULL,
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS queue (
    queue_id       SERIAL PRIMARY KEY,
    customer_id    INT NOT NULL REFERENCES customer(customer_id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    estimated_wait INT DEFAULT 0,
    served_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS queue_history (
    history_id       SERIAL PRIMARY KEY,
    customer_id      INT NOT NULL REFERENCES customer(customer_id) ON DELETE CASCADE,
    served_time      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    service_duration INT NOT NULL
);

INSERT INTO service_counter (counter_name)
SELECT 'Counter 1' WHERE NOT EXISTS (SELECT 1 FROM service_counter);
