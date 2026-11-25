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
| FastAPI 8000 | model 네트워크, 호스트 포트 없음 |
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
