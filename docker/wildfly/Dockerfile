# =============================================================================
# A WildFly image pre-configured for this application
# =============================================================================
# The stock WildFly image knows nothing about PostgreSQL. Rather than configure
# a running container by hand - which lasts exactly until the container is
# recreated - we bake the configuration into an image. The result is
# REPRODUCIBLE: anyone who builds this Dockerfile gets a byte-identical server.
# =============================================================================

# Pinned to an exact version, never `:latest`. A floating tag means your build
# silently changes underneath you and "it worked yesterday" becomes unanswerable.
# The image runs on JDK 21, matching maven.compiler.release=21 in pom.xml. Those
# two numbers are allowed to differ - bytecode built for an older release runs on
# any newer JVM - but they must never differ the OTHER way: bytecode compiled for
# a release the server's JVM predates fails at class-load time with
# UnsupportedClassVersionError, and it fails on deploy, not on build.
#
# WildFly 41 is a Jakarta EE 11 server, and EE 11 requires Java 17 as a floor.
# There is no jdk17 tag published for 41.x, so jdk21 is the baseline here.
FROM quay.io/wildfly/wildfly:41.0.1.Final-jdk21

# A build argument, overridable with --build-arg. Keeping the version here rather
# than buried in a URL makes upgrading a one-line change.
ARG POSTGRES_JDBC_VERSION=42.7.13

ENV JBOSS_HOME=/opt/jboss/wildfly

# The base image runs as the unprivileged `jboss` user - correct, and worth
# preserving. We switch to root only to create a directory, then switch straight
# back. A container process running as root is a container escape away from being
# root on the host.
USER root
RUN mkdir -p ${JBOSS_HOME}/customization && \
    chown -R jboss:root ${JBOSS_HOME}/customization

# ADD (not COPY) can fetch a URL. Downloading the driver at build time keeps
# binaries out of the git repository.
ADD --chown=jboss:root \
    https://repo1.maven.org/maven2/org/postgresql/postgresql/${POSTGRES_JDBC_VERSION}/postgresql-${POSTGRES_JDBC_VERSION}.jar \
    ${JBOSS_HOME}/customization/postgresql.jar

COPY --chown=jboss:root configure.cli ${JBOSS_HOME}/customization/configure.cli

USER jboss

# Apply the configuration script, then delete the history WildFly keeps of
# standalone.xml. Without this the image carries a pile of backup copies and,
# worse, the server can decide to roll back to one on boot.
RUN ${JBOSS_HOME}/bin/jboss-cli.sh --file=${JBOSS_HOME}/customization/configure.cli && \
    rm -rf ${JBOSS_HOME}/standalone/configuration/standalone_xml_history/current

# A management user for the admin console on :9990.
#
# Hard-coded credentials are acceptable ONLY because this is a local development
# image that never leaves your machine. In anything real these come from a
# secret manager, and the management port is not exposed at all.
RUN ${JBOSS_HOME}/bin/add-user.sh admin "Admin#2026" --silent

# 8080 = application traffic, 9990 = management console.
EXPOSE 8080 9990

# -b 0.0.0.0            listen on all interfaces for application traffic
# -bmanagement 0.0.0.0  same for the management interface
#
# Both are required inside a container: the default binds to 127.0.0.1, which
# means "reachable only from inside the container" - so your port mapping would
# appear to do nothing. This catches nearly everyone once.
CMD ["/opt/jboss/wildfly/bin/standalone.sh", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]
