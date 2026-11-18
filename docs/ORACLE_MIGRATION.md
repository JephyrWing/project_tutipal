# Oracle 서버 이전 준비 순서 (프로토타입)

> PT-82 프로토타입 문서다. 이전 계획이 확정되지 않았고, 아래 단계는 아직 실제 서버에서 실행·검증하지 않았다.
> 이미지 빌드 방식과 열리는 포트 정책은 [DEPLOYMENT.md](DEPLOYMENT.md) 3~4장을 따른다.

대상: Oracle Cloud **ARM(Ampere A1) + Ubuntu**. CD는 `linux/arm64` 이미지만 만든다.
관리 PC는 Windows이며, PowerShell의 기본 `ssh`/`scp`를 사용한다.

## 0. 먼저 정할 값

| 항목 | 용도 | 들어가는 곳 |
| --- | --- | --- |
| Spring 공개 도메인 | HTTPS 접속점(Cloudflare) | `APP_ALLOWED_ORIGINS`, Tunnel hostname |
| Cloudflare Tunnel | Spring 8080만 외부 공개 | `CLOUDFLARE_TUNNEL_TOKEN` |
| Docker Hub 계정 | 이미지 저장소 | GitHub Secrets, 서버 `docker login`(비공개일 때) |
| S3 버킷·리전·키 | 운영 자료 원본 | `S3_BUCKET`, `AWS_*` |
| 모델 제공자 | AI 추론 | `MODEL_*`, `OPENAI_*` |
| PM 승인 | `ENABLE_CD` 활성화·외부 배포 | Jira |

모델: 예제의 `MODEL_BASE_URL`은 호스트 Ollama(`host.docker.internal:11434`)다. ARM 서버에서 로컬 모델을 돌릴지, `auto`로 OpenAI를 쓸지 정한다. OpenAI를 쓰면 입력이 외부로 전송되고 비용이 생기므로 승인을 받는다.

## 1. 인스턴스 생성 (Oracle 콘솔)

1. Compute → Instances → Create.
2. Image: **Canonical Ubuntu (aarch64)**. Shape: **VM.Standard.A1.Flex**. OCPU·메모리는 콘솔에 표시되는 무료 한도 안에서 고른다.
3. SSH 키: Windows에서 만든 공개키를 등록한다.
   ```powershell
   ssh-keygen -t ed25519 -f $HOME\.ssh\tutipal_oracle
   # $HOME\.ssh\tutipal_oracle.pub 내용을 콘솔에 붙여 넣는다. 개인키는 공유하지 않는다.
   ```
4. 공인 IP를 **Reserved(고정)**로 바꾼다. 재시작해도 IP가 바뀌지 않아야 `LIVEKIT_NODE_IP`와 `EC2_HOST`가 유지된다.

## 2. 네트워크 방화벽 (콘솔)

VCN → Subnet → Security List(또는 NSG)의 Ingress 규칙:

| 포트 | 출발지 | 용도 |
| --- | --- | --- |
| `22/tcp` | 관리자 IP만 권장 | SSH. GitHub Actions 배포도 이 포트를 쓰므로, CD를 켜면 IP 제한 방식을 다시 정한다 |
| `7881/tcp` | `0.0.0.0/0` | LiveKit WebRTC(TCP) |
| `7882/udp` | `0.0.0.0/0` | LiveKit WebRTC(UDP) |

`8080`, `5432`, `8000`, `7880`은 열지 않는다. Spring은 Cloudflare Tunnel로만 공개한다.

## 3. 서버 기본 설정

```powershell
ssh -i $HOME\.ssh\tutipal_oracle ubuntu@<공인IP>
```

```bash
sudo apt-get update && sudo apt-get -y upgrade
sudo timedatectl set-timezone Asia/Seoul

# Oracle Ubuntu는 서버 안 iptables도 기본으로 막는다. 콘솔 규칙과 별도로 연다.
sudo iptables -I INPUT -p tcp --dport 7881 -j ACCEPT
sudo iptables -I INPUT -p udp --dport 7882 -j ACCEPT
sudo netfilter-persistent save

# Docker Engine + Compose plugin
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu   # 배포 스크립트는 sudo 없이 docker를 실행한다
exit                             # 다시 접속해야 그룹이 적용된다
```

재접속 후 확인:

```bash
docker version && docker compose version
uname -m          # aarch64 이어야 한다
```

## 4. 앱 디렉터리와 비밀값

```bash
mkdir -p ~/app/infra
```

Windows에서 배포 정의를 처음 한 번 올린다(이후에는 CD가 같은 커밋 기준으로 덮어쓴다).

```powershell
scp -i $HOME\.ssh\tutipal_oracle infra/docker-compose.prod.yml infra/deploy.sh infra/.env.prod.example ubuntu@<공인IP>:app/infra/
```

서버에서 `.env.prod`를 만든다. 이 파일은 Git·Jira·Drive에 올리지 않는다.

```bash
cd ~/app/infra
cp .env.prod.example .env.prod && chmod 600 .env.prod
nano .env.prod
```

반드시 바꿀 값:

- `DOCKERHUB_USERNAME`, `IMAGE_TAG`(배포할 커밋 SHA)
- `DB_PW`, `AI_INTERNAL_KEY`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`: 충분히 긴 임의 값
- `LIVEKIT_NODE_IP`: 1단계의 **고정 공인 IP**. Cloudflare IP가 아니다
- `SESSION_COOKIE_SECURE=true`, `APP_ALLOWED_ORIGINS=https://<Spring 도메인>`
- `S3_BUCKET`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- `CLOUDFLARE_TUNNEL_TOKEN`, `CLOUDFLARED_IMAGE`(검증한 태그로 고정)
- OAuth 앱을 쓰면 `GOOGLE_*`, `NAVER_*`, `KAKAO_*`. 각 제공자 콘솔의 리디렉트 주소도 새 도메인으로 등록한다

비공개 Docker Hub 저장소라면 `docker login`도 해 둔다.

## 5. Cloudflare Tunnel

1. Cloudflare Zero Trust → Tunnels → 새 Tunnel을 만들고 토큰을 `.env.prod`에 넣는다.
2. Public hostname: `<Spring 도메인>` → Service `http://backend:8080` (컨테이너 Tunnel 기준).
3. 컨테이너 Tunnel을 쓰므로 GitHub 변수 `ENABLE_CLOUDFLARE=true`, 수동 실행 시 `--profile cloudflare`.

## 6. 첫 배포는 수동으로

CD를 켜기 전에 서버에서 직접 올려 확인한다. 이미지가 Docker Hub에 arm64로 있어야 한다(7단계의 첫 CD 실행이나 수동 빌드).

```bash
cd ~/app
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare config --quiet
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare pull
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml --profile cloudflare up -d --wait
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml ps
```

확인할 것:

- 모든 컨테이너가 `healthy`/`running`, `docker image inspect <이미지> --format '{{.Architecture}}'`가 `arm64`
- Flyway 마이그레이션 로그 정상(`docker compose ... logs backend`)
- `https://<Spring 도메인>`으로 Electron 로그인
- 외부망(휴대폰 핫스팟 등)에서 화면공유 연결. 7881/7882와 `LIVEKIT_NODE_IP` 확인 대상이다

`config` 결과 전체나 `.env.prod`를 증거에 붙이지 않는다.

## 7. GitHub 설정과 CD 활성화

| 이름 | 종류 | 값 |
| --- | --- | --- |
| `EC2_HOST` | Secret | 고정 공인 IP |
| `EC2_USER` | Secret | `ubuntu` |
| `EC2_SSH_KEY` | Secret | CD 전용 개인키(별도 키 권장, 공개키는 서버 `~/.ssh/authorized_keys`에 추가) |
| `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` | Secret | Docker Hub |
| `ENABLE_CLOUDFLARE` | Variable | `true` |
| `ENABLE_CD` | Variable | **PM 승인 후** `true` |

활성화 후 `main` 반영 → CI → ARM 러너에서 arm64 빌드 → SSH로 `infra/deploy.sh` 실행 순서로 진행된다. 이 브랜치의 `deploy.yml` 변경이 `main`에 들어가야 arm64 빌드가 적용된다.

## 8. 운영 전 남은 확인

- DB 백업: 볼륨 백업 또는 정기 `pg_dump`. Flyway는 자동 롤백이 없으므로 배포 전 백업 방침을 정한다
- 디스크·메모리 여유, 컨테이너 재시작(`restart: unless-stopped`) 후 정상 복구
- 외부 WebRTC가 막히는 네트워크(학교·회사망) 대비 TURN 필요 여부
- Oracle 무료 인스턴스 회수 정책과 결제 계정 상태
- 결과를 Jira(PT-82)에 작업 키·SHA·시각(KST)·명령·종료 코드로 기록
