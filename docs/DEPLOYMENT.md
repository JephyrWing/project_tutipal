# 배포 준비 안내

## 1. 배포 형태

현재 목표는 개인 PC에서 서버 컨테이너들을 실행하고 Cloudflare로 Spring의 HTTPS/WSS 접속점을 공개하는 것이다. Electron은 각 사용자 PC에 설치한다. 이후 Linux 서버를 구하면 같은 Compose와 GitHub Actions를 사용한다.

Mindot과 동일한 `main` 푸시 → Docker Hub 이미지 빌드/업로드 → SSH 배포 → `~/app/infra/docker-compose.prod.yml` 적용 흐름을 남겼다. Secrets 이름도 유지했다. TUTIPAL에서는 프론트 웹 컨테이너를 없애고 백엔드·FastAPI·DB 이미지가 빌드된다. LiveKit은 공식 이미지를 사용한다.

`ENABLE_CD`가 `true`일 때만 이미지 업로드/배포한다. 기본은 CI 빌드 확인만 실행한다. 업무 기능은 이식했으며 외부 배포 환경과 Secrets·DNS/Tunnel은 별도 준비해야 한다. 공개 GitHub 저장소의 생성·소스 push는 웹 서비스 외부 배포와 구분한다.

2026-11-17에 Windows 개발 PC에서 backend/FastAPI/DB 이미지 빌드와 로컬 Compose 전체 기동, Flyway V1 및 pgvector 0.8.7 적용을 확인했다. Electron Windows NSIS 설치 파일도 생성했다. 이 결과는 외부 배포나 GitHub Actions 실행 성공을 뜻하지 않는다. npm/Python/Gradle 잠금 파일을 포함하며 CI는 `npm ci`, Python 잠금 파일 설치, Windows NSIS 빌드를 사용한다. 실제 결과는 [개발 안내](DEVELOPMENT.md)의 8장에 기록했다.

## 2. 개인 PC에서 먼저 실행할 때

처음에는 이미지 레지스트리 없이 로컬 빌드로 서버를 실행할 수 있다. [DEVELOPMENT.md](DEVELOPMENT.md)의 로컬 Compose는 loopback으로 Spring을 공개하므로, 호스트에서 실행 중인 Cloudflare Tunnel의 원점을 `http://127.0.0.1:8080`으로 지정할 수 있다.

외부 공개 전에는 `SESSION_COOKIE_SECURE=true`, `APP_ALLOWED_ORIGINS=https://실제-Spring-도메인`, HTTPS를 설정하고 개발 로그인을 끈다. 인증·LiveKit 중계와 참가자 정리는 구현했으며 외부 재접속·미디어·장애 동작은 별도 검증한다. 같은 PC 실행 확인과 외부 수업 운영을 구분한다.

Docker Hub 이미지 방식은 다음과 같다.

```powershell
Copy-Item infra/.env.prod.example infra/.env.prod
# 사용자명, 이미지 SHA, 비밀값, S3/미디어 IP, Cloudflare 토큰을 실제 값으로 설정
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare pull
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare up -d --wait
```

Cloudflare Tunnel이 이미 호스트에서 실행 중이면 `--profile cloudflare`는 생략한다. 컨테이너 Tunnel의 서비스 주소는 `http://backend:8080`, 호스트 Tunnel의 주소는 `http://127.0.0.1:8080`이다.

## 3. 서버 확보 후 CI/CD 활성화

다음은 **Linux + Docker Compose + SSH** 서버 기준이다. Windows 개인 PC는 현재 수동 Compose 실행 대상이며, 이 SSH 배포 스크립트를 그대로 사용하는 대상은 아니다.

| GitHub 설정 | 종류 | 값 |
| --- | --- | --- |
| `DOCKERHUB_USERNAME` | Secret | Docker Hub 사용자명 |
| `DOCKERHUB_TOKEN` | Secret | 이미지 업로드 권한 토큰 |
| `EC2_HOST` | Secret | SSH 대상 호스트. 이름은 Mindot과 같고 EC2 외 서버도 가능 |
| `EC2_USER` | Secret | SSH 사용자 |
| `EC2_SSH_KEY` | Secret | 대상 서버 접속 개인키 |
| `ENABLE_CD` | Repository variable | 준비 완료 후 `true`로 설정 |
| `ENABLE_CLOUDFLARE` | Repository variable | 컨테이너 Tunnel 사용 시 `true` |

서버에는 Docker Engine, Compose plugin, SSH 및 `tar`가 필요하다. SSH 사용자는 `sudo` 없이 Docker를 실행할 수 있어야 한다. 이 작업이 서버 권한을 자동 변경하지는 않는다. 비공개 Docker Hub 저장소라면 서버에서도 미리 `docker login`을 해 둔다.

서버의 `~/app/infra/.env.prod`를 직접 만들고 실제 값을 넣는다. workflow는 이 비밀 파일을 전송하거나 덮어쓰지 않는다. 도입할 `CLOUDFLARED_IMAGE`도 태그/다이제스트로 고정한다. S3 버킷 생성·권한 부여, Cloudflare hostname/Tunnel, WebRTC 방화벽·NAT·TURN 준비는 별도다.

`main`에 들어온 코드로 먼저 CI를 수행한 뒤 이미지에 `latest`와 커밋 SHA 태그를 붙인다. 배포에는 SHA를 사용하고 동일 커밋의 Compose/배포 스크립트를 업로드한다. 서버에서는 `pull` 후 `up --wait`로 컨테이너 준비 상태를 확인한다. 실패 시 workflow는 실패로 끝나며 자동 DB 롤백은 하지 않는다. Flyway는 앞으로 호환 가능한 변경과 백업 방침을 정해야 한다.

CD는 해당 실행의 SHA를 환경변수로 전달하므로 `.env.prod`의 예전 `IMAGE_TAG`보다 우선한다. 이후 호스트에서 수동 `up`을 할 때도 원하는 SHA를 지정하거나 `.env.prod`의 `IMAGE_TAG`를 갱신해야 한다. 단순 재시작은 `docker compose ... restart`를 사용한다.

## 4. 외부에 열리는 범위

| 구성 | 기본 정책 |
| --- | --- |
| Spring 8080 | 호스트 loopback 및 Cloudflare Tunnel에서만 접속 |
| FastAPI 8000 | model 네트워크, 호스트 포트 없음 |
| PostgreSQL 5432 | 내부 data 네트워크, 호스트 포트 없음 |
| LiveKit 7880 | realtime 네트워크, 원본 제어/API 포트 비공개 |
| LiveKit 7881/tcp, 7882/udp | WebRTC 미디어 경로. 방화벽·라우터와 `LIVEKIT_NODE_IP` 확인 필요 |

Cloudflare Tunnel로 Spring HTTPS가 연결돼도 WebRTC 미디어가 자동으로 통과하는 것은 아니다. LiveKit은 도달 가능한 미디어 IP/포트 또는 TURN이 필요하다. 현재 공인 IP/포트포워딩/TURN은 미정이므로 외부 화면공유는 검증 과제로 남는다. [LiveKit 포트 안내](https://docs.livekit.io/transport/self-hosting/ports-firewall/)와 [배포 안내](https://docs.livekit.io/transport/self-hosting/deployment/)를 참고한다.

## 5. 아직 정해야 할 실제 값

지금 골격 생성에는 비밀값이나 도메인이 필요하지 않아 예제로 남겼다. 실제 연결·배포 시 GitHub 저장소, Spring 공개 도메인, Cloudflare Tunnel, Docker Hub 계정, 서버 SSH 정보, S3 버킷/리전/권한, LiveKit 미디어 외부 경로, 모델 제공자를 정한다. 서버 비밀값은 Electron의 Vite 변수에 넣지 않는다.
