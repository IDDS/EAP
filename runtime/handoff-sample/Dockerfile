ARG FRAMEWORK_BASE
FROM ${FRAMEWORK_BASE}
COPY handoff_worker.py /opt/eap-handoff/handoff_worker.py
COPY eap_execution.py eap_links.py /opt/eap-handoff/
COPY eap-handoff-source eap-handoff-target /usr/local/bin/
RUN chmod 0755 /usr/local/bin/eap-handoff-source /usr/local/bin/eap-handoff-target
ENTRYPOINT ["/usr/local/bin/eap-handoff-source"]
CMD []
