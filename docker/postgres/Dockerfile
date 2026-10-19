# The official image plus the legacy time zone names. The JDBC driver sends
# the JVM's time zone when it connects, and Java on Windows reports old
# aliases such as America/Buenos_Aires (for America/Argentina/Buenos_Aires).
# Debian 13, which the image is built on, moved those to tzdata-legacy, so
# without it Postgres rejects the connection: invalid value for parameter
# "TimeZone".
FROM postgres:17

RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata-legacy \
    && rm -rf /var/lib/apt/lists/*
