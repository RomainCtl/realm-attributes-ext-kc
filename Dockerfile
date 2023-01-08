# Build
FROM quay.io/keycloak/keycloak:20.0.2 as builder

# Enable health and metrics support
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

# Configure a database vendor
ENV KC_DB=postgres

WORKDIR /opt/keycloak

# for demonstration purposes only, please make sure to use proper certificates in production instead
RUN keytool -genkeypair -storepass password -storetype PKCS12 -keyalg RSA -keysize 2048 -dname "CN=server" -alias server -ext "SAN:c=DNS:localhost,IP:127.0.0.1" -keystore conf/server.keystore

# A example build step that downloads a JAR file from a URL and adds it to the providers directory (work also for themes)
#RUN curl -sL <MY_PROVIDER_JAR_URL> -o /opt/keycloak/providers/myprovider.jar
COPY target/*.jar /opt/keycloak/providers/

RUN /opt/keycloak/bin/kc.sh build

# Create optimized image
FROM quay.io/keycloak/keycloak:20.0.2
COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
