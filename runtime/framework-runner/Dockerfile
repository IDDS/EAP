ARG PYTHON_BASE
FROM ${PYTHON_BASE}
USER 0:0
WORKDIR /
COPY site /opt/eap-framework/site
COPY observers /opt/eap-framework/
COPY worker.py resend.py /opt/eap-framework/
COPY handoff_worker.py /opt/eap-handoff/handoff_worker.py
COPY eap-langgraph-runner eap-autogen-runner /usr/local/bin/
RUN chmod 0755 /usr/local/bin/eap-langgraph-runner /usr/local/bin/eap-autogen-runner \
    && test ! -e /usr/bin/python3 && ln -s /usr/local/bin/python3 /usr/bin/python3
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["/usr/local/bin/eap-langgraph-runner"]
CMD []
