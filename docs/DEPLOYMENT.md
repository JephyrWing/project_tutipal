# 배포 준비 안내

## 1. 배포 형태

현재 목표는 개인 PC에서 서버 컨테이너들을 실행하고 Cloudflare로 Spring의 HTTPS/WSS 접속점을 공개하는 것이다. Electron은 각 사용자 PC에 설치한다. 이후 Linux 서버를 구하면 같은 Compose와 GitHub Actions를 사용한다.

`main` 푸시 → CI → GitHub Container Registry(GHCR) 이미지 빌드/업로드 → SSH 배포 → `~/app/infra/docker-compose.prod.yml` 적용 흐름이다. 처음 Mindot에서 가져온 Docker Hub 방식은 2026-11-23에 GHCR로 바꿨다(별도 계정·토큰 없이 `GITHUB_TOKEN` 사용). TUTIPAL에서는 프론트 웹 컨테이너를 없애고 백엔드·FastAPI·DB 이미지가 빌드된다. LiveKit은 공식 이미지를 사용한다.

`ENABLE_CD`가 `true`일 때만 이미지 업로드/배포한다. 기본은 CI 빌드 확인만 실행한다. 업무 기능은 이식했으며 외부 배포 환경과 Secrets·DNS/Tunnel은 별도 준비해야 한다. 공개 GitHub 저장소의 생성·소스 push는 웹 서비스 외부 배포와 구분한다.

2026-11-17에 로컬 이미지 빌드·Compose 전체 기동·Flyway V1~V12·pgvector0.8.7·앱 실제 동작을 확인했고 공개 GitHub 저장소에 소스를 올렸다. GitHub Actions에서도 Windows NSIS, Spring JAR, Python/Compose 검사가 통과했다. 이미지 업로드/SSH 배포는 실행하지 않았다. 실제 결과는 [개발 안내](DEVELOPMENT.md)의 10~11장에 기록했다.

## 2. 개인 PC에서 먼저 실행할 때

처음에는 이미지 레지스트리 없이 로컬 빌드로 서버를 실행할 수 있다. [DEVELOPMENT.md](DEVELOPMENT.md)의 로컬 Compose는 loopback으로 Spring을 공개하므로, 호스트에서 실행 중인 Cloudflare Tunnel의 원점을 `http://127.0.0.1:8080`으로 지정할 수 있다.

외부 공개 전에는 `SESSION_COOKIE_SECURE=true`, `APP_ALLOWED_ORIGINS=https://실제-Spring-도메인`, HTTPS를 설정하고 개발 로그인을 끈다. 인증·LiveKit 중계와 참가자 정리는 구현했으며 외부 재접속·미디어·장애 동작은 별도 검증한다. 같은 PC 실행 확인과 외부 수업 운영을 구분한다.

레지스트리 이미지 방식은 다음과 같다.

```powershell
Copy-Item infra/.env.prod.example infra/.env.prod
# IMAGE_REGISTRY(ghcr.io/소문자-소유자), 이미지 SHA, 비밀값, S3/미디어 IP, Cloudflare 토큰을 실제 값으로 설정
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare pull
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare up -d --wait
```

Cloudflare Tunnel이 이미 호스트에서 실행 중이면 `--profile cloudflare`는 생략한다. 컨테이너 Tunnel의 서비스 주소는 `http://backend:8080`, 호스트 Tunnel의 주소는 `http://127.0.0.1:8080`이다.

## 3. 서버 확보 후 CI/CD 활성화

다음은 **Linux + Docker Compose + SSH** 서버 기준이다. Windows 개인 PC는 현재 수동 Compose 실행 대상이며, 이 SSH 배포 스크립트를 그대로 사용하는 대상은 아니다.

운영 서버는 **AWS EC2 서울(x86_64)**로 준비 중이다(PT-82, PT-110). 서버 준비 순서와 수동 빌드·실행은 [AWS_MIGRATION.md](AWS_MIGRATION.md)를 따른다. 이미지는 amd64다.

| GitHub 설정 | 종류 | 값 |
| --- | --- | --- |
| `EC2_HOST` | Secret | SSH 대상 호스트(EC2 퍼블릭 IP/DNS). EC2 외 서버도 가능 |
| `EC2_USER` | Secret | SSH 사용자(Ubuntu AMI는 `ubuntu`) |
| `EC2_SSH_KEY` | Secret | 대상 서버 접속 개인키(.pem 내용 전체) |
| `ENABLE_CD` | Repository variable | 준비 완료 후 `true`로 설정 |
| `ENABLE_CLOUDFLARE` | Repository variable | 컨테이너 Tunnel 사용 시 `true` |

Secrets·variables는 저장소 소유자(admin)만 설정할 수 있다. 이미지는 `ghcr.io/<소문자 소유자>/tutipal-back|tutipal-fastapi|tutipal-db`로 올라가고, 업로드와 서버 pull 모두 실행 중인 작업의 `GITHUB_TOKEN`을 쓴다. 서버의 `deploy.sh`는 끝날 때(성공·실패·중단 신호 모두) `docker logout ghcr.io`로 토큰을 남기지 않는다. 이 토큰은 작업이 끝나면 만료된다. 다만 ssh-action은 `envs`를 원격 명령 문자열의 `export`로 넘기므로 배포가 도는 동안 서버의 `ps`에 보일 수 있다. 그래서 서버에는 배포 사용자 외 로그인 계정을 두지 않는다. appleboy 액션은 태그 대신 커밋 SHA로 고정했다.

EC2 보안 그룹의 22/tcp는 `ENABLE_CD` 전까지 관리자 IP `/32`만 허용한다. GitHub 호스티드 러너는 IP가 매번 바뀌므로, CD 활성화 시 GitHub 러너용 개방 여부와 방식은 PM과 결정한다. 어느 경우든 서버는 키 인증만 허용한다(Ubuntu EC2 기본값: `PasswordAuthentication no`).

레지스트리 변수 이름이 바뀌었다. 기존 EC2 .env.prod의 DOCKERHUB_USERNAME=local → IMAGE_REGISTRY=local로 이름을 바꾼 뒤 아래 "직접 빌드한 이미지(`IMAGE_REGISTRY=local`)" 명령으로 다시 띄운다. 바꾸지 않으면 `Set IMAGE_REGISTRY` 오류로 멈춘다. `local` 이미지는 레지스트리에 없어서 `deploy.sh`(항상 `pull`부터 한다)는 실패하므로 쓰지 않는다. CD를 켜면 `.env.prod`의 `IMAGE_REGISTRY`도 `ghcr.io/<소문자 소유자>`로 바꿔 둔다. 남겨 두면 CD 배포는 환경변수 덕분에 되지만 이후 수동 `up`·롤백이 `local` 이미지를 찾는다.

서버에는 Docker Engine, Compose plugin, SSH 및 `tar`가 필요하다. SSH 사용자는 `sudo` 없이 Docker를 실행할 수 있어야 한다(`docker` 그룹). 이 작업이 서버 권한을 자동 변경하지는 않는다.

서버의 `~/app/infra/.env.prod`를 직접 만들고 실제 값을 넣는다. workflow는 이 비밀 파일을 전송하거나 덮어쓰지 않는다. `CLOUDFLARED_IMAGE`는 아직 `latest`이며 고정은 별도 작업으로 미뤘다(6장 확인 명령 참고). S3 버킷 생성·권한 부여, Cloudflare hostname/Tunnel, WebRTC 방화벽·NAT·TURN 준비는 별도다.

`main`에 들어온 코드로 먼저 CI를 수행한 뒤 이미지에 `latest`와 커밋 SHA 태그를 붙인다. 배포에는 SHA를 사용하고 동일 커밋의 Compose/배포 스크립트를 업로드한다. 서버에서는 `pull` 후 `up --wait`로 컨테이너 준비 상태를 확인한다. 실패 시 workflow는 실패로 끝나며 자동 DB 롤백은 하지 않는다. 대신 `deploy.sh`는 앱 컨테이너를 바꾸기 전에 매번 DB를 `~/backups/tutipal-<시각>-pre-<태그 12자>.dump`(pg_dump custom 형식)로 백업하고 최근 10개만 남긴다. 태그는 환경변수 `IMAGE_TAG`, 없으면 `.env.prod`의 `IMAGE_TAG`이며 영문·숫자·`._-` 외 문자는 `_`로 바꾼다. DB 컨테이너가 멈춰 있으면 지금 이미지 그대로 먼저 켜고(`up --no-recreate --wait tutipal-db`), 컨테이너가 없으면 만든 뒤 백업한다. 그래서 데이터 볼륨이 있는데 백업 없이 새 Flyway 마이그레이션이 도는 일은 없다. DB가 뜨지 않거나 백업이 실패하면 배포를 멈춘다. 만든 지 7일 넘었고 컨테이너가 쓰지 않는 이미지는 디스크 확보를 위해 지운다(`docker image prune -af --filter until=168h`). 레지스트리 이미지는 다시 pull하면 되지만, 서버에서 직접 빌드한 `local/...` 이미지는 이때 지워져 그 SHA로 롤백할 수 없으므로 다시 빌드해야 한다.

CD는 해당 실행의 `IMAGE_REGISTRY`·SHA를 환경변수로 전달하므로 `.env.prod`의 값보다 우선한다. 이후 호스트에서 수동 `up`을 할 때도 원하는 SHA를 지정하거나 `.env.prod`의 `IMAGE_TAG`를 갱신해야 한다. 단순 재시작은 `docker compose ... restart`를 사용한다. DB 스키마는 어느 방식으로도 되돌아가지 않으므로 필요하면 백업 dump를 `pg_restore`로 복원한다. 컨테이너 Tunnel을 쓰면 아래 명령에 `--profile cloudflare`(deploy.sh는 `ENABLE_CLOUDFLARE=true`)를 붙인다.

이전 버전으로 되돌리기 — GHCR 이미지(CD 사용 시):

```bash
cd ~/app
# 공개 패키지
IMAGE_REGISTRY=ghcr.io/<소문자 소유자> IMAGE_TAG=<이전 SHA> sh infra/deploy.sh
# 비공개 패키지면 read:packages 권한만 준 PAT를 넘긴다. deploy.sh가 끝날 때 로그아웃한다.
# 토큰이 셸 기록에 남지 않게 read -rs로 입력받는다.
read -rs GHCR_TOKEN && GHCR_USER=<GitHub 사용자> GHCR_TOKEN="$GHCR_TOKEN" \
  IMAGE_REGISTRY=ghcr.io/<소문자 소유자> IMAGE_TAG=<이전 SHA> sh infra/deploy.sh; unset GHCR_TOKEN
```

`docker login ghcr.io`를 직접 했다면 PAT가 `~/.docker/config.json`에 남으므로 끝나면 반드시 `docker logout ghcr.io`를 실행한다. 되돌린 SHA를 계속 쓸 것이면 `.env.prod`의 `IMAGE_TAG`도 맞춘다.

직접 빌드한 이미지(`IMAGE_REGISTRY=local`, 현재 EC2 수동 운영) — `deploy.sh` 대신 pull 없이 띄운다. 해당 SHA의 `local/...` 이미지가 서버에 남아 있어야 한다(`docker image ls 'local/*'`로 확인, 없으면 위 AWS 이전 안내의 4단계로 다시 빌드). 이 경로는 DB 백업을 하지 않으므로 필요하면 먼저 `pg_dump`를 직접 남긴다.

```bash
cd ~/app
IMAGE_TAG=<SHA> docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml up -d --wait
```

운영 프로필(`application-prod.yaml`)은 `server.forward-headers-strategy: native`로 cloudflared의 `X-Forwarded-Proto`를 믿는다. 그래야 OAuth `redirect_uri`가 https로 만들어진다. 서버 전용 override 파일은 필요 없다. Tomcat 기본 신뢰 범위는 사설·loopback 주소 전체라 같은 Docker 네트워크의 fastapi·livekit과 호스트 127.0.0.1에서 온 헤더도 믿는다(앱에 IP 기반 판단은 없음. cloudflared로 좁히는 설정은 후속 과제).

믿는 것은 `X-Forwarded-Proto`(https 여부)와 `X-Forwarded-For`뿐이다. `server.tomcat.remoteip.host-header`·`port-header`를 빈 값으로 두어 `X-Forwarded-Host`/`X-Forwarded-Port`는 읽지 않는다. 호스트는 cloudflared가 그대로 넘기는 원래 `Host`(공개 도메인)로 정해지므로, 클라이언트가 이 헤더를 보내 Cloudflare를 통과해도 `redirect_uri`가 바뀌지 않는다. 실제 Tomcat에서 이 동작을 `ProdForwardedHeadersTest`로 확인한다. 배포 후 서버에서 한 번 확인한다.

```bash
curl -sI https://tutipal.faultfinder.link/oauth2/authorization/google | grep -i '^location'
curl -sI -H 'X-Forwarded-Host: evil.example' https://tutipal.faultfinder.link/oauth2/authorization/google | grep -i '^location'
# 두 결과 모두 redirect_uri가 https://tutipal.faultfinder.link/login/oauth2/code/google 이어야 한다.
```

redirect_uri 호스트가 `backend:8080`처럼 나오면 cloudflared가 원래 Host를 넘기지 않는 설정(`httpHostHeader` 등)이다. 그때는 Tunnel 설정에서 Host 변경을 없애는 것이 먼저다. 당장 필요하면 임시로 `.env.prod`에 `SERVER_TOMCAT_REMOTEIP_HOST_HEADER=X-Forwarded-Host`를 넣고 `docker-compose.prod.yml`의 backend `environment`로 전달(별도 작업)하되, Cloudflare Transform Rule로 클라이언트가 보낸 `X-Forwarded-Host`를 지운 뒤에 쓴다.

## 4. 외부에 열리는 범위

| 구성 | 기본 정책 |
| --- | --- |
| Spring 8080 | 호스트 loopback 및 Cloudflare Tunnel에서만 접속 |
| FastAPI 8000 | PT-110 기본: 팀 PC의 `127.0.0.1:8000`만 열고 EC2는 AI 전용 Tunnel(②)+Access로만 접근(7장). EC2 같은 호스트 실행(`--profile ec2-ai`) 시 model 네트워크, 호스트 포트 없음 |
| PostgreSQL 5432 | 내부 data 네트워크, 호스트 포트 없음 |
| LiveKit 7880 | realtime 네트워크, 원본 제어/API 포트 비공개 |
| LiveKit 7881/tcp, 7882/udp | WebRTC 미디어 경로. 방화벽·라우터와 `LIVEKIT_NODE_IP` 확인 필요 |

Cloudflare Tunnel로 Spring HTTPS가 연결돼도 WebRTC 미디어가 자동으로 통과하는 것은 아니다. LiveKit은 도달 가능한 미디어 IP/포트 또는 TURN이 필요하다. 현재 공인 IP/포트포워딩/TURN은 미정이므로 외부 화면공유는 검증 과제로 남는다. [LiveKit 포트 안내](https://docs.livekit.io/transport/self-hosting/ports-firewall/)와 [배포 안내](https://docs.livekit.io/transport/self-hosting/deployment/)를 참고한다.

## 5. 아직 정해야 할 실제 값

지금 골격 생성에는 비밀값이나 도메인이 필요하지 않아 예제로 남겼다. 실제 연결·배포 시 GitHub 저장소, Spring 공개 도메인, Cloudflare Tunnel, 서버 SSH 정보, S3 버킷/리전/권한, LiveKit 미디어 외부 경로, 모델 제공자를 정한다. 서버 비밀값은 Electron의 Vite 변수에 넣지 않는다.

## 6. 버전 표시와 비밀값 점검

backend 이미지는 빌드 인자 `GIT_SHA`·`APP_VERSION`을 같은 이름의 환경변수로 가진다. CD는 `GIT_SHA=<커밋 SHA>`와 `tutipal_back/build.gradle`의 `version`을 넘기고, 이미지 라벨 `org.opencontainers.image.revision`에도 SHA를 남긴다. 인자 없이 빌드하면 둘 다 `unknown`이다. Spring은 기동 완료 시 한 줄만 남긴다.

```bash
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml logs backend | grep 'TUTIPAL started'
# 예: TUTIPAL started version=0.0.1-SNAPSHOT sha=<배포한 커밋 SHA 40자>
```

배포 후 비밀값이 이미지·로그·저장소에 없는지 확인한다. 출력에 실제 값이 보이면 공유하지 말고 해당 값을 교체한다.

```bash
IMG=<레지스트리>/tutipal-back:<SHA>
docker history --no-trunc "$IMG"                       # 빌드 단계에 비밀값·ARG 값이 없어야 한다(GIT_SHA/APP_VERSION만)
docker run --rm --entrypoint env "$IMG" | sort         # 기본 이미지 변수 외에 추가된 것은 GIT_SHA/APP_VERSION뿐이어야 한다
# 우리가 넣는 위치만 찾는다(/etc/ssl/certs 등 기본 CA 인증서 *.pem은 정상이라 제외). 셸을 거치지 않아 따옴표가 한 겹이다.
docker run --rm --user root --entrypoint find "$IMG" /app /tmp /home /root \( -name '.env*' -o -name '*.pem' -o -name '*.key' \) -print   # 출력 없음
docker run --rm --entrypoint cat "$IMG" /app/server.jar > /tmp/server.jar && unzip -l /tmp/server.jar | grep -E '(^|[ /])\.env|\.(pem|key)$'; rm -f /tmp/server.jar   # 출력 없음(JAR 안에도 없어야 한다. unzip이 없으면 apt로 설치)
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml logs backend | grep -iE 'secret|password|token'   # 값이 찍힌 줄이 없어야 한다
git grep -nE '(AKIA[0-9A-Z]{16}|gh[p]_|eyJ[A-Za-z0-9]{20,})'   # 저장소에서 0건([p]는 이 줄 자신과 맞지 않게 하려는 표기)
ls -l ~/app/infra/.env.prod                            # -rw------- (600)
```

`.dockerignore`가 `.env*`, `.git`, `*.pem`을 빌드 컨텍스트에서 뺀다. 운영 값은 서버의 `.env.prod`에서 실행 시에만 주입한다.

`CLOUDFLARED_IMAGE` 고정 전에는 사용할 태그의 다이제스트를 확인하고, 고정할 때 `cloudflare/cloudflared:<tag>@sha256:<digest>` 형식으로 적는다.

```bash
docker buildx imagetools inspect cloudflare/cloudflared:<tag>
```

## 7. PC AI 노드 분리 (PT-110)

### 7.1 구성

```text
Electron 앱 ──HTTPS/WSS──▶ tutipal.<도메인> ─(Cloudflare Tunnel ①)─▶ EC2: Spring · PostgreSQL · LiveKit
                                                                         │ AI가 필요할 때만
                                                                         ▼
          https://ai.<도메인> ─ Cloudflare Access(서비스 토큰) ─(Tunnel ②, PC에서 바깥으로 연결)─▶ 팀 PC
                                                                         │
                                                         cloudflared ─▶ FastAPI(fastapi:8000, 127.0.0.1:8000)
                                                                         │
                                                         Windows Ollama(GPU: gemma3:4b, bge-m3, 이후 채점 모델)
```

| 구분 | EC2 (`infra/docker-compose.prod.yml`) | 팀 PC (`infra/docker-compose.ai-node.yml`) |
| --- | --- | --- |
| 실행 | backend, tutipal-db, livekit, cloudflared(①) | fastapi, cloudflared(②), PC에 설치한 Ollama |
| 책임 | 로그인·권한·저장·개인정보·RAG 검색·컨텍스트 구성 | 전달받은 입력의 계산만(채팅·임베딩·재순위화·집중도·군집화) |
| 자격 증명 | DB·S3·LiveKit·OAuth, `AI_INTERNAL_KEY`, `CF_ACCESS_CLIENT_ID/SECRET` | `AI_INTERNAL_KEY`(EC2와 같은 값), `CLOUDFLARE_AI_TUNNEL_TOKEN`. DB·S3·LiveKit 자격 증명 없음 |
| 외부 공개 | Tunnel ①의 Spring만 | 없음. 포트포워딩 없이 cloudflared가 바깥으로만 연결. FastAPI는 PC loopback `127.0.0.1:8000`만 |
| env 예제 | `infra/.env.prod.example` | `infra/.env.ai-node.example` |

- 앱은 PC에 직접 닿지 않는다. `ai.<도메인>`을 앱·`VITE_` 변수·Electron 설정에 넣지 않는다.
- 인증은 두 겹이다. Cloudflare Access가 서비스 토큰(`CF-Access-Client-Id`/`CF-Access-Client-Secret`)이 없는 요청을 PC 앞에서 막고, FastAPI가 `X-Internal-Key`를 다시 확인한다(`tutipal_ai/app/core/security.py`). 둘 중 하나라도 틀리면 AI 호출은 실패하고 앱에는 "AI 조교에 연결할 수 없어요"(503)가 보인다.
- Spring의 AI 클라이언트 네 곳(`AiClient`, `ClusterClient`, `RerankClient`, `FocusClient`)은 공통 `AiAccessHeaders` 한 곳에서 Access 헤더를 붙인다. 값이 둘 다 비면 헤더를 보내지 않고(같은 호스트 FastAPI), 하나만 있으면 Spring이 시작하지 않는다. 값은 로그에 남기지 않는다.
- EC2 같은 호스트에서 FastAPI를 돌리던 이전 방식은 `--profile ec2-ai`(deploy.sh는 `ENABLE_EC2_AI=true`)와 `FAST_API_BASE_URL=http://fastapi:8000`으로 남겨 두었다. 기본은 PC 노드다.

### 7.2 Cloudflare 준비 (대시보드, 관리자)

1. Networking → Tunnels → Create Tunnel `tutipal-ai`(①과 별도) → 토큰을 PC의 `infra/.env.ai-node` `CLOUDFLARE_AI_TUNNEL_TOKEN`에만 넣는다.
2. Published application: `ai.<도메인>` → `http://fastapi:8000`(compose 서비스 이름. `127.0.0.1`이 아니다).
3. Zero Trust → Access → Service Auth → Service Token 생성. Client ID/Secret은 생성 화면에서 한 번만 보이므로 EC2 `infra/.env.prod`의 `CF_ACCESS_CLIENT_ID`/`CF_ACCESS_CLIENT_SECRET`로 바로 옮긴다. 만료일을 Jira(PT-110)에 기록하고 만료 전에 교체한다.
4. Access → Applications → Self-hosted `ai.<도메인>`, 정책 동작 **Service Auth**, 포함 조건은 3의 토큰만. 사람 로그인(이메일·IdP) 정책은 넣지 않는다.
5. Bot Fight Mode 등 봇 차단이 켜져 있으면 EC2의 Java/curl 요청이 도전 페이지(HTML)를 받을 수 있다. 7.5 확인에서 200이 아니면 먼저 이 설정과 Access 정책을 본다.

토큰·Tunnel ID·서비스 토큰 이름은 공개 저장소에 적지 않고 Jira에만 남긴다.

### 7.3 팀 PC 준비와 실행

필요: Windows + NVIDIA GPU, Docker Desktop(WSL2), Windows용 Ollama.

```powershell
ollama pull gemma3:4b
ollama pull bge-m3
Copy-Item infra/.env.ai-node.example infra/.env.ai-node
# AI_INTERNAL_KEY(EC2와 같은 값), CLOUDFLARE_AI_TUNNEL_TOKEN, 필요하면 FOCUS_MODEL_DIR/PATH/SHA256을 실제 값으로
docker compose --env-file infra/.env.ai-node -f infra/docker-compose.ai-node.yml config --quiet
docker compose --env-file infra/.env.ai-node -f infra/docker-compose.ai-node.yml up -d --build --wait
curl.exe -s http://127.0.0.1:8000/health
# {"status":"ok","model_ready":true,"local_chat_ready":true,"local_embedding_ready":true,...}
docker compose --env-file infra/.env.ai-node -f infra/docker-compose.ai-node.yml logs --tail 20 cloudflared
# "Registered tunnel connection" 줄이 보여야 한다(토큰 값은 출력하지 않는다)
```

- `MODEL_PROVIDER` 기본은 `ollama`다. `auto`에 `OPENAI_API_KEY`를 넣으면 PC 모델이 없을 때 OpenAI로 넘어가 입력이 외부로 나가므로(AGENTS.md 7장) PM 승인 없이 쓰지 않는다.
- 집중도 모델은 이미지에 없다. `FOCUS_MODEL_DIR`(PC 폴더, 비우면 git 제외 폴더 `infra/ai-node-models`)이 `/models/focus`에 읽기 전용으로 붙고, `FOCUS_MODEL_PATH=/models/focus/<파일>.txt`와 `FOCUS_MODEL_SHA256`을 넣는다.
- 재순위화를 켤 때는 `WITH_RERANKER=true`로 다시 빌드하고 `RERANK_ENABLED=true`, EC2의 `TUTIPAL_RAG_RERANK_ENABLED=true`를 함께 켠다.

### 7.4 EC2 설정

`~/app/infra/.env.prod`에 다음을 넣는다(값은 화면·Git·Jira에 남기지 않는다).

- `FAST_API_BASE_URL=https://ai.<도메인>`(끝 `/` 없음). 비어 있으면 compose가 `Set FAST_API_BASE_URL`로 멈춘다.
- `AI_INTERNAL_KEY`: PC `infra/.env.ai-node`와 **같은 값**. 다르면 FastAPI가 401을 돌려준다.
- `CF_ACCESS_CLIENT_ID`, `CF_ACCESS_CLIENT_SECRET`: 7.2-3의 서비스 토큰. 둘 다 넣거나 둘 다 비운다.
- `TUTIPAL_RAG_RERANK_TIMEOUT`, `APP_FOCUS_PREDICT_TIMEOUT`: 기본 `2s`. 7.6 측정에서 터널 왕복 때문에 부족할 때만 늘린다. PC의 `RERANK_QUEUE_TIMEOUT_MS`는 늘린 재순위화 제한보다 작게 둔다.

```bash
cd ~/app
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare config --quiet
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare up -d --wait --remove-orphans
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml ps   # fastapi가 목록에 없어야 한다
```

이전에 같은 EC2에서 돌던 `fastapi` 컨테이너가 남아 있으면 `docker compose ... --profile ec2-ai stop fastapi && docker compose ... --profile ec2-ai rm -f fastapi`로 정리한다. 이전 방식으로 되돌릴 때는 `FAST_API_BASE_URL=http://fastapi:8000`, Access 값 비움, `--profile ec2-ai`(deploy.sh는 `ENABLE_EC2_AI=true`)로 다시 띄운다. CD 워크플로는 `ENABLE_EC2_AI`를 넘기지 않으므로 CD 배포는 항상 PC 노드 구성이다.

### 7.5 연결 확인 (EC2에서)

비밀값이 명령줄·셸 기록·`ps`에 남지 않게 헤더를 권한 600 임시 파일로 넘긴다(`curl -H @파일`).

```bash
cd ~/app && umask 077
AI=https://ai.<도메인>
val() { sed -n "s/^$1=//p" infra/.env.prod | tail -n 1 | tr -d '\r'; }
HA=$(mktemp); H=$(mktemp)   # HA: Access 토큰만, H: Access 토큰 + 내부 키
{ echo "CF-Access-Client-Id: $(val CF_ACCESS_CLIENT_ID)"
  echo "CF-Access-Client-Secret: $(val CF_ACCESS_CLIENT_SECRET)"; } > "$HA"
{ cat "$HA"; echo "X-Internal-Key: $(val AI_INTERNAL_KEY)"; } > "$H"

curl -sS -o /dev/null -w '%{http_code}\n' "$AI/health"                 # 토큰 없음: 403 또는 302(Access가 막음)
curl -sS -H @"$HA" "$AI/health"                                         # 200, model_ready=true
curl -sS -o /dev/null -w '%{http_code}\n' -H @"$HA" -H 'Content-Type: application/json' \
  -d '{"texts":["확인"]}' "$AI/v1/embed"                                # 내부 키 없음: 401
curl -sS -o /dev/null -w '%{http_code}\n' -H @"$H" -H 'Content-Type: application/json' \
  -d '{"texts":["확인"]}' "$AI/v1/embed"                                # 200 (AI_INTERNAL_KEY 양쪽 일치)
rm -f "$HA" "$H"
```

마지막 명령이 401이면 EC2와 PC의 `AI_INTERNAL_KEY`가 다르다. 앱에서 로그인한 뒤 AI 채팅·자료 업로드가 되는지도 확인한다. PC를 끈 상태에서 채팅을 보내 "AI 조교에 연결할 수 없어요"가 보이고 Cloudflare 오류 페이지 내용이 앱에 나오지 않는지 확인한다.

### 7.6 응답 시간 측정 (PT-29 기준 비교용)

목적: Tunnel ②를 지나도 PT-29 수치 기준을 지키는지 본다. 측정 위치는 EC2(Spring과 같은 위치)이고, 결과는 Jira(PT-110, PT-29)에 작업 키·SHA·시각(KST)·모델·반복 횟수와 함께 남긴다.

- **첫 응답 시간**: 요청을 보낸 뒤 첫 `delta`(답변 첫 조각)를 받기까지. `/v1/chat`은 응답 헤더를 바로 보내므로 curl의 `time_starttransfer`는 첫 조각 시간이 아니다. 아래 스크립트로 잰다.
- **완료 시간**: 마지막 줄까지 받은 시간(`time_total`, 스크립트의 `total`).
- **콜드/웜**: 콜드는 PC에서 `ollama stop gemma3:4b`(또는 keep-alive 시간 경과) 직후 첫 요청, 웜은 바로 이어지는 요청.
- **반복**: 콜드 3회, 웜 10회 이상. 중앙값과 최댓값을 적는다. 100초에 가까운 값이 있으면 7.8의 위험으로 따로 표시한다.

헤더 파일 `$H`(Access 토큰 + 내부 키)는 7.5와 같은 방법으로 만들고 끝나면 지운다.

```bash
# 임베딩·짧은 호출: 연결/첫 바이트/전체 시간(초)
for i in $(seq 1 10); do
  curl -sS -o /dev/null -H @"$H" -H 'Content-Type: application/json' -d '{"texts":["광합성은 빛 에너지를 쓴다"]}' \
    -w '%{http_code} connect=%{time_connect} tls=%{time_appconnect} first_byte=%{time_starttransfer} total=%{time_total}\n' \
    "$AI/v1/embed"
done

# 채팅 스트림: 첫 delta 시간과 완료 시간(초). 반복은 for 문으로 감싼다.
python3 - "$H" "$AI" <<'PY'
import json, sys, time, urllib.request
headers = dict(l.rstrip("\n").split(": ", 1) for l in open(sys.argv[1], encoding="utf-8") if ": " in l)
headers["Content-Type"] = "application/json"
body = json.dumps({"system": "두 문장으로 답한다.", "messages": [{"role": "user", "content": "광합성을 설명해 줘"}]}).encode()
request = urllib.request.Request(sys.argv[2] + "/v1/chat", data=body, headers=headers, method="POST")
start, first, deltas, error = time.monotonic(), None, 0, False
with urllib.request.urlopen(request, timeout=300) as response:
    head = time.monotonic() - start
    for raw in response:
        line = raw.strip()
        if not line:
            continue
        item = json.loads(line)
        if "error" in item:
            error = True
            break
        if "delta" in item:
            deltas += 1
            first = first if first is not None else time.monotonic() - start
total = time.monotonic() - start
print(f"headers={head:.2f} first_delta={'-' if first is None else f'{first:.2f}'} total={total:.2f} deltas={deltas} error={error}")
PY
```

기록 양식(같은 표를 Jira에 붙인다):

| 회차 | 시각(KST) | 호출 | 콜드/웜 | 상태 | 첫 응답(초) | 완료(초) | 비고(모델·입력 길이·동시 요청) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 |  | /v1/chat | 콜드 |  |  |  |  |
| 2 |  | /v1/chat | 웜 |  |  |  |  |
| 3 |  | /v1/embed | 웜 |  | (first_byte) |  |  |
| 요약 |  |  |  |  | 중앙값 / 최댓값 | 중앙값 / 최댓값 | PT-29 기준 충족 여부 |

앱 전체 경로(앱 → ① → Spring → ② → PC)는 Electron 개발자 도구 Network의 `/api/tutor/chat` EventStream에서 첫 `delta` 이벤트 시각과 마지막 이벤트 시각으로 같은 표에 따로 적는다. 위 EC2 측정과 섞어 집계하지 않는다.

### 7.7 운영 규칙

- 수업·시연 중 PC 절전·최대 절전·화면 잠금 후 절전을 끈다(전원 옵션: 절전 "안 함", 덮개 닫기 "아무 것도 안 함"). Windows 업데이트 재시작은 사용 시간 밖으로 미룬다.
- 수업·시연 중에는 GPU를 학습(PT-88/89 파인튜닝 등)·게임·다른 모델에 쓰지 않는다. 학습은 수업 밖 시간에 하고, 끝나면 `ollama ps`와 `nvidia-smi`로 GPU가 비었는지 확인한다.
- PC 부팅 시 자동 기동: Docker Desktop "Start when you sign in"(Windows 로그인이 필요하다), compose `restart: unless-stopped`, Ollama 시작 프로그램 등록(Windows 설치본 기본값). 재부팅 후 7.3의 `/health`와 cloudflared 로그를 확인한다.
- 수업 전 콜드 시작을 줄이려면 PC 사용자 환경변수 `OLLAMA_KEEP_ALIVE`(예: `1h`)를 두고 수업 시작 전에 채팅 한 번으로 모델을 올려 둔다.
- `AI_INTERNAL_KEY`를 바꿀 때는 EC2와 PC를 같이 바꾸고 둘 다 다시 띄운다. Access 서비스 토큰도 만료 전에 새 토큰을 정책에 추가 → EC2 교체 → 이전 토큰 삭제 순서로 바꾼다.

### 7.8 장애 동작과 남은 위험

**PC가 꺼졌거나 Tunnel ②가 끊겼을 때.** Cloudflare는 502/504/524 또는 530(오류 1033) HTML을, Access는 토큰 오류에 302/403을 돌려준다. Spring은 상태 코드만 보고 본문은 읽어 넘기지 않는다. 채팅·요약·임베딩·군집화는 `AiUnavailableException` → 앱에는 503 `{"status":503,"message":"AI 조교에 연결할 수 없어요"}`, 재순위화는 코사인 순서로 전환, 집중도는 "알 수 없음"이다(`AiNodeConnectionTest`가 연결 거부·502/503/504/524/530·302를 모두 확인).

**자료 업로드 임베딩 실패(PT-99 경로 확인 결과).** `MaterialIndexWorker`는 FastAPI가 붙이는 `503 + X-Embedding-Failure: transient`(Ollama 연결 실패 확인)이나 연결 실패만 자동 재시도(10초, 30초 뒤 두 번)로 본다. PC가 꺼져 Cloudflare 530 등이 오면 자동 재시도 없이 `FAILED`, `index_retryable=true`, 오류 문구 "임베딩을 만들지 못했어요. 잠시 후 다시 시도해 주세요"가 된다. 선생님이 PC를 켠 뒤 자료 화면의 다시 처리(`requestIndex` → `queueIndex`)를 누르면 `PROCESSING`으로 돌아가 처음부터 다시 인덱싱된다. 즉 복구는 되지만 자동이 아니라 수동 재처리다. FastAPI는 켜져 있고 Ollama만 꺼진 경우는 자동 재시도 대상이다.

**짧은 타임아웃.** 재순위화(`tutipal.rag.rerank.timeout`)와 집중도(`app.focus.predict-timeout`)는 응답 본문까지 포함한 전체 2초 제한이고, 이미 설정값이다. compose가 `TUTIPAL_RAG_RERANK_TIMEOUT`, `APP_FOCUS_PREDICT_TIMEOUT`(기본 2s)으로 넘긴다. 연결 제한 1초는 코드 상수지만 전체 제한 안에 포함된다. 터널 왕복이 길어 시간 초과가 잦으면 7.6 측정 뒤 값을 늘린다. 재순위화는 실패해도 코사인 순서로 답하고, 집중도는 "알 수 없음"이 된다.

**긴 응답·콜드 시작과 Cloudflare 100초 제한.** 판단 근거:

- 앱 ← Spring(Tunnel ①): `TutorController`와 `ConversationTurnController`가 주기적으로 SSE 주석 `: ping`을 보내 이미 대비돼 있다.
- Spring ← FastAPI(Tunnel ②) Agent 경로(`/v1/agent/chat`): FastAPI가 첫 조각 전까지 10초마다 `{"ping":true}`를 보내고 120초 마감이 있다.
- 기본 채팅 경로(`/v1/chat`)에는 ping 줄이 없다. 다만 `StreamingResponse`가 응답 헤더를 바로 보내므로 Cloudflare의 "100초 안에 응답 헤더" 조건에는 걸리지 않고, 생성이 시작되면 조각이 계속 온다. 남는 공백은 첫 조각 전 대기(모델 적재 5~30초 + 입력 처리)다. GPU의 gemma3:4b에서는 100초보다 충분히 짧다고 보고 이번에는 코드를 바꾸지 않았다. FastAPI에 하트비트를 넣으려면 생성기를 별도 작업으로 옮겨야 해서(Agent 경로의 `_pump` 방식) 취소·시간 제한 동작이 바뀐다. Spring은 빈 줄을 무시하므로 필요하면 빈 줄 하트비트로 호환성을 지키며 추가할 수 있다.
- 남은 위험: Ollama가 다른 긴 요청을 처리하는 동안 대기열에 걸리면 첫 조각 전 공백이 100초에 가까워질 수 있다. 응답 헤더를 한꺼번에 보내는 호출(`/v1/embed` 최대 125초, `/v1/agent/summarize`, `/v1/insights/cluster`)이 100초를 넘으면 Cloudflare가 524로 끊고 Spring은 AI 연결 불가로 처리한다(임베딩이면 위의 수동 재처리). 7.6 측정에서 첫 응답·완료 시간이 60초를 넘는 경우가 보이면 `/v1/chat` 빈 줄 하트비트를 후속 작업으로 넣는다.

**그 밖에.** PC 한 대라 이중화가 없다(PC 장애 = AI 기능 중단, 로그인·자료·화면공유는 EC2에서 계속 동작). 실제 Tunnel ② 연결과 Access 정책 동작은 이 문서 작성 시점에 시험하지 않았다(7.5에서 처음 확인).
