FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY Deployer.java .
RUN javac --release 21 -d /out Deployer.java

FROM alpine:3.22
# git + ssh for the pull, the docker CLI with compose and buildx for the build.
RUN apk add --no-cache openjdk21-jre-headless git openssh-client docker-cli docker-cli-compose docker-cli-buildx

# Runs as the host user who owns the apps' folders (DEPLOY_UID/DEPLOY_GID in .env):
# files git touches keep their owner, git doesn't call the repos "dubious", and ssh
# needs the uid to exist in /etc/passwd. Its ~/.ssh is mounted from the host.
ARG DEPLOY_UID=1000
ARG DEPLOY_GID=1000
RUN (getent group "$DEPLOY_GID" >/dev/null || addgroup -g "$DEPLOY_GID" deploy) \
 && adduser -D -u "$DEPLOY_UID" -G "$(getent group "$DEPLOY_GID" | cut -d: -f1)" -h /home/deploy deploy

COPY --from=build /out /app
USER deploy
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx48m", "-cp", "/app", "Deployer"]
