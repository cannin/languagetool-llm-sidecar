# syntax=docker/dockerfile:1

ARG LT_GIT_REF=1e5a1a64f3b80072ccf221b221d02e50dc2c71d1

FROM maven:3.9-eclipse-temurin-17 AS sidecar-build
WORKDIR /build/sidecar
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM maven:3.9-eclipse-temurin-17 AS languagetool-build
ARG LT_GIT_REF
WORKDIR /build/languagetool
RUN git init \
    && git remote add origin https://github.com/languagetool-org/languagetool.git \
    && git fetch --depth 1 origin "${LT_GIT_REF}" \
    && git checkout --detach FETCH_HEAD
RUN mvn -B -DskipTests -DskipITs -pl languagetool-standalone -am package \
    && mkdir -p /out/languagetool \
    && cp -a languagetool-standalone/target/LanguageTool-*/. /out/languagetool/

FROM eclipse-temurin:17-jre
WORKDIR /opt

COPY --from=languagetool-build /out/languagetool /opt/languagetool
COPY --from=sidecar-build /build/sidecar/target/languagetool-llm-sidecar.jar \
    /opt/sidecar/languagetool-llm-sidecar.jar
COPY remote-rules.json /config/remote-rules.json
COPY languagetool-server.properties.example /config/languagetool.properties
COPY docker/sidecar.properties /config/sidecar.properties
COPY rules /config/rules
COPY docker/entrypoint.sh /usr/local/bin/languagetool-entrypoint

RUN chmod 0755 /usr/local/bin/languagetool-entrypoint \
    && groupadd --gid 10001 languagetool \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --shell /usr/sbin/nologin languagetool \
    && mkdir -p /data/cache \
    && chown -R 10001:10001 /data

USER 10001:10001

EXPOSE 8081

ENTRYPOINT ["/usr/local/bin/languagetool-entrypoint"]
