FROM oracle/graalvm-ce:20.1.0-java11 as builder

WORKDIR /app
COPY . /app

RUN gu install native-image

RUN ./gradlew --no-daemon --console=plain distTar
RUN tar -xvf build/distributions/cloud-build-notifier.tar -C build/distributions

RUN curl -L -o musl.tar.gz https://github.com/gradinac/musl-bundle-example/releases/download/v1.0/musl.tar.gz && \
    tar -xvzf musl.tar.gz

RUN native-image --static --no-fallback --no-server --enable-http --enable-https \
      -H:IncludeResources=logback.xml -H:IncludeResources=application.properties \
      -H:Name=cloud-build-notifier \
      -H:UseMuslC=/app/bundle/ \
      -cp /app/build/distributions/cloud-build-notifier/lib/cloud-build-notifier.jar:/app/build/distributions/cloud-build-notifier/lib/* \
      com.jamesward.cloudbuildnotifier.Server

FROM scratch

COPY --from=builder /app/cloud-build-notifier /cloud-build-notifier

CMD ["/cloud-build-notifier"]
