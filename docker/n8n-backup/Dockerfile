FROM python:3.14-alpine
RUN addgroup -g 1000 backup && adduser -D -u 1000 -G backup backup \
    && mkdir /control /backups /n8n-data && chown backup:backup /control /backups /n8n-data
WORKDIR /app
COPY manager.py test_manager.py ./
RUN chmod 0755 /app && chmod 0644 /app/manager.py /app/test_manager.py
USER 1000:1000
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
RUN python -m unittest -v
ENTRYPOINT ["python", "/app/manager.py"]
