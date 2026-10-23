FROM docker.m.daocloud.io/library/python@sha256:0687a6bc9716edc2a6ee0fbfb0f87e7ee358b262b67c9215de91bc9b2d38ba71
WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --no-deps --require-hashes -r requirements.txt
COPY adapter.py server.py /app/
USER 65532:65532
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8091
CMD ["python", "server.py"]
