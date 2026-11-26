# Cloudflare Tunnel

현재 개인 PC 배포를 위한 선택 구성이다. Cloudflare에서 토큰 방식의 Tunnel을 만들고 Public hostname의 서비스 주소를 **`http://backend:8080`**으로 설정한다. Electron은 해당 HTTPS 도메인으로 Spring에 접근한다. `/ws`를 포함한 제어/업무 WebSocket도 Spring이 처리할 예정이다.

토큰을 `infra/.env.prod`의 `CLOUDFLARE_TUNNEL_TOKEN`에 넣고 `--profile cloudflare`로 실행한다. 토큰과 자격 증명 파일은 Git에 넣지 않는다. `CLOUDFLARED_IMAGE`는 실제 도입 시 확인한 태그/다이제스트로 고정한다. 예제의 `latest`는 선택 구성 자리표시자다.

Cloudflare Tunnel이 호스트에서 별도로 실행 중이면 컨테이너를 중복 실행하지 않고 기존 Tunnel의 원점을 `http://127.0.0.1:8080`으로 설정할 수 있다. 이때 Compose의 cloudflare profile은 사용하지 않는다.

원본 LiveKit `7880`, FastAPI `8000`, PostgreSQL `5432`에는 이 Spring용 Tunnel에서 Public hostname을 만들지 않는다. PT-110의 AI 전용 Tunnel(②)만 예외로, 팀 PC의 `infra/docker-compose.ai-node.yml`이 `ai.<도메인>` → `http://fastapi:8000`을 연결하고 Cloudflare Access 서비스 토큰과 내부 키를 둘 다 요구한다([DEPLOYMENT.md](../../docs/DEPLOYMENT.md) 7장). LiveKit 미디어 `7881/tcp`, `7882/udp`는 별도 경로다. 공인 IP/공유기 포트포워딩/방화벽 또는 TURN 경로를 확정해야 외부 화면공유가 된다. Cloudflare의 Spring용 HTTPS Tunnel만 켠 상태를 화면공유 배포 완료로 보지 않는다.
