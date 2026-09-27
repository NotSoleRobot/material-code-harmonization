FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application source and models
COPY src/ ./src/
COPY models/ ./models/
COPY app.py .

# WP0/M-07: set PYTHONPATH so src/ subpackages are importable without sys.path hacks
ENV PYTHONPATH=/app/src
ENV PORT=5000

EXPOSE 5000

CMD ["sh", "-c", "exec gunicorn -w 2 -b 0.0.0.0:${PORT:-5000} --preload app:app"]
