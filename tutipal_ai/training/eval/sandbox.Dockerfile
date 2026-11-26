# 모델 생성 코드 채점용 이미지. 표준 라이브러리 + pytest만 둔다.
# 빌드: docker build -t tutipal-eval-sandbox:1 -f eval/sandbox.Dockerfile eval
FROM python:3.12-slim
RUN pip install --no-cache-dir pytest==9.2.0
