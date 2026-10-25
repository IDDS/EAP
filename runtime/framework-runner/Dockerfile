ARG PYTHON_BASE
FROM ${PYTHON_BASE}
USER 0:0
WORKDIR /
COPY site /opt/eap-framework/site
COPY observers /opt/eap-framework/
COPY worker.py resend.py /opt/eap-framework/
COPY eap-langgraph-runner eap-autogen-runner /usr/local/bin/
RUN test ! -e /usr/bin/python3 && ln -s /usr/local/bin/python3 /usr/bin/python3
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["/usr/local/bin/eap-langgraph-runner"]
CMD []
