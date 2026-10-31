FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --no-create-home sim
COPY plc_sim plc_sim
USER sim
# 4840: OPC UA, 1102: S7 (ISO-on-TCP; a real CPU listens on 102, which needs root inside the container)
EXPOSE 4840 1102
ENV PLC_S7_PORT=1102
ENTRYPOINT ["python", "-m", "plc_sim"]
