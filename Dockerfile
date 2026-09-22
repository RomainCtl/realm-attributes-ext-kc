ARG KEYCLOAK_VERSION=26.7.4

# Build
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder

ARG KEYCLOAK_VERSION

# Health and metrics support
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Database vendor (overridable at runtime via KC_DB_*)
ENV KC_DB=postgres

# Enable the declarative-ui feature (experimental) so the
# UiTabProvider SPI is wired into ServerInfo and rendered
# by the keycloak.v2 admin console.
ENV KC_FEATURES=declarative-ui

WORKDIR /opt/keycloak

# Demonstration only - replace with proper certificates in production.
RUN keytool -genkeypair -storepass password -storetype PKCS12 -keyalg RSA \
        -keysize 2048 -dname "CN=server" -alias server \
        -ext "SAN:c=DNS:localhost,IP:127.0.0.1" \
        -keystore conf/server.keystore

# Drop the provider JAR(s) built by `mvn package` into /opt/keycloak/providers.
COPY target/*.jar /opt/keycloak/providers/

# Build the optimized Keycloak image with declarative-ui enabled.
RUN /opt/keycloak/bin/kc.sh build

# Create optimized image
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}

COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
