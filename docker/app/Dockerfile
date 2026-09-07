# =============================================================================
# THE APPLICATION IMAGE
# =============================================================================
# A MULTI-STAGE build, and the reason is worth understanding before the syntax.
#
# The naive Dockerfile installs Maven, copies the source in, builds, and ships
# the result. What it actually ships is Maven, the JDK, the whole ~/.m2
# repository and every intermediate class file - several hundred megabytes of
# things the running application will never touch, each of which is one more
# package a security scanner will find a CVE in.
#
# Two stages fix that. The first has the tools; the second has only a JRE and
# the jar. Nothing from the first stage reaches the final image except what is
# explicitly copied out of it.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# THE DEPENDENCY LAYER, and the single most useful trick in this file.
#
# Docker caches each instruction and reuses the cache while the inputs are
# unchanged. Copying the pom ALONE and resolving dependencies first means that
# layer is rebuilt only when the pom changes - so an ordinary source edit
# re-runs the compile and nothing else, and a build that would take three
# minutes takes fifteen seconds.
#
# Copy the whole source first and this optimisation disappears silently: every
# edit invalidates the dependency layer too.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are run by CI, not by the image build. A container build that runs the
# test suite needs a database it does not have, and turns "deploy the thing CI
# already verified" into "verify it again, differently, with less".
RUN mvn -B -q clean package -DskipTests

# -----------------------------------------------------------------------------
# Stage 2: run
# -----------------------------------------------------------------------------
# A JRE, not a JDK: no compiler, no javadoc tool, no jshell. Smaller, and a
# smaller attack surface.
FROM eclipse-temurin:21-jre

# DO NOT RUN AS ROOT. The default user in a container is root, and a process
# that is compromised is then root inside the container - which, with a few
# kernel or misconfiguration bugs, is a route to root on the host. Creating an
# unprivileged user costs two lines.
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

WORKDIR /app
COPY --from=build --chown=spring:spring /build/target/enrollment.jar app.jar

EXPOSE 8080

# -XX:MaxRAMPercentage rather than -Xmx: the JVM sizes its heap from the
# CONTAINER's memory limit, so changing the limit in docker-compose.yml is
# enough and there is no second number to keep in step. A JVM that ignores the
# cgroup limit is the classic cause of a container killed by the OOM killer
# with no Java exception anywhere in the logs.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
