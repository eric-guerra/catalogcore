CREATE TABLE products (
    id               BIGSERIAL PRIMARY KEY,
    sku              VARCHAR(40)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    description      TEXT,
    brand            VARCHAR(80),
    category_path    VARCHAR(300) NOT NULL,
    price_cents      BIGINT       NOT NULL CHECK (price_cents >= 0),
    sale_price_cents BIGINT       CHECK (sale_price_cents >= 0),
    stock_qty        INT          NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    rating           DOUBLE PRECISION,
    review_count     INT          NOT NULL DEFAULT 0,
    image_url        VARCHAR(500),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version          BIGINT       NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP    NOT NULL
);

CREATE TABLE product_attributes (
    product_id BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    name       VARCHAR(60)  NOT NULL,
    attr_value VARCHAR(200) NOT NULL, -- not "value": reserved in H2 2.x and the SQL standard
    PRIMARY KEY (product_id, name)
);

CREATE TABLE index_outbox (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT      NOT NULL,
    op          VARCHAR(10) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  VARCHAR(500),
    created_at  TIMESTAMP   NOT NULL
);
CREATE INDEX idx_outbox_pending ON index_outbox (attempts, id);
