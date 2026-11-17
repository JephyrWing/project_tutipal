FROM postgres:17.12-bookworm AS extension
ARG PGVECTOR_VERSION=0.8.7
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates curl postgresql-server-dev-17 \
    && curl -fsSL "https://github.com/pgvector/pgvector/archive/refs/tags/v${PGVECTOR_VERSION}.tar.gz" -o /tmp/pgvector.tar.gz \
    && mkdir /tmp/pgvector \
    && tar -xzf /tmp/pgvector.tar.gz -C /tmp/pgvector --strip-components=1 \
    && cd /tmp/pgvector \
    && make OPTFLAGS="" \
    && make install

FROM postgres:17.12-bookworm
COPY --from=extension /usr/lib/postgresql/17/lib/vector.so /usr/lib/postgresql/17/lib/vector.so
COPY --from=extension /usr/share/postgresql/17/extension/vector* /usr/share/postgresql/17/extension/
