FROM @RUNTIME_IMAGE@
RUN command -v curl && useradd --system --uid 10001 eap && mkdir -p /app && chown eap:eap /app
WORKDIR /app
COPY --chown=10001:10001 app.jar /app/eap.jar
USER eap
EXPOSE 8080 4317
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/eap.jar"]
