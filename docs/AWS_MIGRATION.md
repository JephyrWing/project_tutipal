# AWS 운영 서버 준비 순서

> PT-82 프로토타입에서 시작해 PT-110에서 `develop` 기준으로 정리했다. 처음 검토한 Oracle Cloud ARM은 2026-11-18 가입 심사 거절로 쓰지 않는다.
> 1~6단계는 2026-11-18~19(KST)에 실제 서버에서 실행했다. 7~8단계와 앱 로그인·화면공유 확인은 남아 있다.
> 이미지 빌드·CD 방식, 열리는 포트 정책, 버전·비밀값 점검은 [DEPLOYMENT.md](DEPLOYMENT.md) 3~4장과 6장을 따른다.
> 실제 버킷 이름, IAM 역할 이름, Tunnel ID, OAuth 앱 ID 같은 운영 식별자는 공개 저장소에 적지 않고 Jira(PT-82, PT-110)에만 남긴다.

대상: AWS EC2 **서울(ap-northeast-2), x86_64, Ubuntu 24.04**. 이미지는 amd64다.
관리 PC는 Windows이며 기본 `ssh`를 사용한다.

## 0. 정한 값

| 항목 | 값 | 들어가는 곳 |
| --- | --- | --- |
| Spring 공개 도메인 | `tutipal.faultfinder.link` (Cloudflare DNS) | `APP_ALLOWED_ORIGINS`, Tunnel route |
| 서버 | EC2 `t3.large`(2 vCPU/8 GiB), gp3 30 GiB, Elastic IP | `LIVEKIT_NODE_IP`, `EC2_HOST` |
| 운영 방식 | 필요할 때만 켜고 쓰지 않을 때 중지(총 사용 시간 예상 24시간 안팎) | — |
| 자료 저장소 | S3 버킷(서울, 퍼블릭 차단). 이 버킷 Get/Put/Delete/List만 허용한 EC2 IAM 역할로 접근한다. 실제 이름은 Jira | `MATERIAL_STORAGE=s3`, `S3_BUCKET` |
| 모델 제공자 | 외부 API(Workers AI 또는 OpenAI) 결정 대기. 외부 전송 고지·동의 필요 | `MODEL_*`, `OPENAI_*` |
| PM 승인 | 담당 변경·일정·비용·`ENABLE_CD` | Jira |

비용(서울, 대략): `t3.large` 시간당 약 $0.104 + 디스크·Elastic IP 월 $6~7. 월 100 GB까지 인터넷 송신 무료라 4명 시연의 화면공유 트래픽은 추가 비용이 거의 없다.

## 1. 인스턴스 생성 (EC2 콘솔)

1. 리전 서울 → 인스턴스 시작 → 이름 `tutipal-prod`.
2. AMI **Ubuntu Server 24.04 LTS (x86)**. 26.04는 Docker 공식 지원 확인 전이라 쓰지 않는다.
3. 유형 `t3.large`, 키 페어 ED25519 `.pem`(개인키는 공유하지 않는다), 스토리지 gp3 30 GiB.
4. Elastic IP를 할당해 연결한다. 중지·재시작해도 IP가 유지되어 `LIVEKIT_NODE_IP`를 다시 바꾸지 않아도 된다.
5. S3 접근용 IAM 역할을 인스턴스에 연결한다(작업 → 보안 → IAM 역할 수정).

## 2. 보안 그룹

| 포트 | 출발지 | 용도 |
| --- | --- | --- |
| `22/tcp` | 관리자 IP(`/32`)만 | SSH. `ENABLE_CD` 전까지 유지하고, CD 활성화 시 GitHub 러너용 개방 여부는 PM과 결정한다 |
| `7881/tcp` | `0.0.0.0/0` | LiveKit WebRTC(TCP) |
| `7882/udp` | `0.0.0.0/0` | LiveKit WebRTC(UDP) |

`8080`, `5432`, `8000`, `7880`, `80/443`은 열지 않는다. Spring은 Cloudflare Tunnel로만 공개한다. Ubuntu AMI는 서버 안 방화벽이 기본으로 열려 있어 iptables 작업은 없다.

## 3. 서버 기본 설정

```powershell
ssh -i $HOME\.ssh\tutipal-key.pem ubuntu@<Elastic IP>
```

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2 docker-buildx git
sudo usermod -aG docker ubuntu   # 다시 접속해야 그룹이 적용된다
```

`docker-buildx`가 없으면 `tutipal_back/Dockerfile`의 `RUN --mount=type=cache`에서 빌드가 실패한다(BuildKit 필요).

## 4. 코드와 이미지

CD를 켜기 전에는 서버에서 직접 빌드한다. 태그는 배포한 커밋 SHA이고, backend에는 기동 로그에 남길 SHA와 버전을 빌드 인자로 넘긴다. 인자를 빼면 로그에 `unknown`이 찍힌다.

```bash
git clone --branch develop https://github.com/JephyrWing/project_tutipal.git ~/app
cd ~/app && SHA=$(git rev-parse HEAD)
APP_VERSION=$(sed -nE "s/^version *= *['\"]([^'\"]+)['\"].*/\1/p" tutipal_back/build.gradle | head -n 1)
DOCKER_BUILDKIT=1 docker build -t local/tutipal-db:$SHA      infra/postgres
DOCKER_BUILDKIT=1 docker build -t local/tutipal-fastapi:$SHA tutipal_ai
DOCKER_BUILDKIT=1 docker build -t local/tutipal-back:$SHA \
  --build-arg GIT_SHA=$SHA --build-arg APP_VERSION=${APP_VERSION:-unknown} tutipal_back
```

## 5. 비밀값 (`~/app/infra/.env.prod`)

```bash
cd ~/app/infra && umask 077 && cp .env.prod.example .env.prod
```

- `IMAGE_REGISTRY=local`, `IMAGE_TAG=<SHA>`(4단계에서 직접 빌드한 이미지). 이전 문서대로 만든 파일은 DEPLOYMENT.md 3장의 변수 이름 변경 안내를 따른다
- `DB_PW`, `AI_INTERNAL_KEY`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`: `openssl rand -hex`로 만든 긴 임의 값
- `LIVEKIT_NODE_IP=<Elastic IP>`, `APP_ALLOWED_ORIGINS=https://tutipal.faultfinder.link`
- S3: `AWS_REGION`(비우면 `ap-northeast-2`), `S3_BUCKET=<버킷 이름>`. EC2 IAM 역할을 연결했으면 `AWS_ACCESS_KEY_ID`·`AWS_SECRET_ACCESS_KEY`는 비워 둔다(AWS SDK 기본 자격 증명 체인이 역할 자격 증명을 쓴다). 역할이 없는 서버에서만 이 버킷 전용 키를 넣는다
- `CLOUDFLARE_TUNNEL_TOKEN`(6단계), OAuth `GOOGLE_*`·`KAKAO_*`·`NAVER_*`
- 값은 화면·Git·Jira·Drive에 남기지 않는다. 콘솔의 복사 버튼으로 클립보드에 담아 서버 파일로 바로 넘긴다

## 6. Cloudflare Tunnel과 실행

1. Cloudflare 대시보드 → Networking → Tunnels → Create Tunnel `tutipal-prod` → Docker 명령의 토큰을 `.env.prod`에 넣는다(Zero Trust 초기 설정 없이 가능).
2. Route → Published application: `tutipal.faultfinder.link` → `http://backend:8080`. `tutipal` CNAME이 자동으로 생긴다.
3. 기존 `faultfinder.link`·`www` 레코드는 이 작업에서 바꾸지 않는다. 다만 연결 대상이던 예전 서버를 2026-11-18에 삭제했으므로 두 레코드는 별도로 정리한다.

```bash
cd ~/app
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare config --quiet
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare up -d --wait
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml logs backend | grep 'TUTIPAL started'   # sha가 $SHA와 같아야 한다
```

확인 결과(2026-11-19): 5개 컨테이너 기동, `https://tutipal.faultfinder.link/actuator/health` 200 `UP`, 외부 공개 포트는 7881/tcp·7882/udp뿐, 8080은 `127.0.0.1`만.

`application-prod.yaml`의 `forward-headers-strategy: native`가 없는 이미지는 OAuth `redirect_uri`를 `http://`로 만든다. PT-110 이전 이미지로 운영할 때는 backend에 `SERVER_FORWARD_HEADERS_STRATEGY=native`를 환경변수로 준다.

WSS: Electron 브리지는 `https://` 서버 주소를 `wss://`로 바꾸고 `Origin`에 서버 origin을 보낸다. STOMP `/ws`와 LiveKit 중계(`/livekit`)는 모두 `APP_ALLOWED_ORIGINS`로 검사하므로 `APP_ALLOWED_ORIGINS=https://tutipal.faultfinder.link` 하나면 된다.

## 7. OAuth

| 제공자 | Redirect URI | 메모 |
| --- | --- | --- |
| Google | `https://tutipal.faultfinder.link/login/oauth2/code/google` | 동의 화면 테스트 모드. 테스트 사용자로 등록한 계정만 로그인 |
| Kakao | `https://tutipal.faultfinder.link/login/oauth2/code/kakao` | Client Secret 필수. `account_email`은 비즈 앱 전환 후 설정 가능(그 전에는 KOE205) |
| Naver | `https://tutipal.faultfinder.link/login/oauth2/code/naver` | 제공 정보: 이름·이메일 |

Electron 설치 앱의 서버 주소 설정은 PT-103의 `docs/RELEASE_CONFIGURATION.md`(PR #38, 아직 `develop`에 병합 전)를 따른다. 병합 전 첫 확인은 실행 환경변수 `TUTIPAL_SPRING_URL=https://tutipal.faultfinder.link`로 한다.

## 8. 남은 확인

- 실제 로그인(각 제공자), 외부망 두 장비 화면공유(7881/7882·`LIVEKIT_NODE_IP`), Electron WSS 연결
- 모델 제공자와 외부 전송 동의 (S3는 2026-11-19 역할 연결 후 컨테이너에서 put/get/list/delete 확인 완료, 앱 업로드 경로는 로그인 후 확인)
- DB 백업: CD의 `deploy.sh`는 배포 전 `pg_dump`를 남긴다. 수동 운영 중에는 정기 `pg_dump`. Flyway는 자동 롤백이 없다
- `CLOUDFLARED_IMAGE` 태그 고정(확인 명령은 DEPLOYMENT.md 6장), Cloudflare 계정 이메일 인증
- CD: GitHub Secrets(`EC2_HOST`=Elastic IP, `EC2_USER`=`ubuntu`, `EC2_SSH_KEY`=CD 전용 키)와 `ENABLE_CD`는 PM 승인 후
- 결과를 Jira(PT-82, PT-110)에 작업 키·SHA·시각(KST)·명령·종료 코드로 기록
