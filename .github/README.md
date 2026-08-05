# .github — GitHub Actions 워크플로

이 디렉터리는 imlate 의 CI/CD 정의만 담는다. 자세한 설정 절차·트러블슈팅은
[`docs/CICD.md`](../docs/CICD.md) 에 있다.

| 파일 | 트리거 | 하는 일 |
| --- | --- | --- |
| `workflows/ci.yml` | `main` 대상 PR, `main` 푸시 | 백엔드 빌드·테스트 / 프론트 타입체크·빌드·E2E / Terraform fmt·validate |
| `workflows/deploy.yml` | `main` 푸시, 수동 실행 | OIDC 인증 후 `infra/scripts/deploy.sh --mode ssm` 으로 EC2 에 배포 |

## 알아 둘 것

- **CI 는 AWS 자격증명을 전혀 쓰지 않는다.** Terraform 잡도 `-backend=false` 로 init 하므로
  원격 상태나 AWS API 를 건드리지 않는다.
- **CD 는 장기 액세스 키를 쓰지 않는다.** OIDC(`aws-actions/configure-aws-credentials`)로
  잡마다 임시 자격증명을 받는다. 저장되는 비밀은 역할 ARN 하나뿐이다.
- **배포 로직은 워크플로에 없다.** 전부 `infra/scripts/deploy.sh` 에 있고 CD 는 그것을 호출만 한다.
  로컬에서 손으로 배포할 때와 CD 가 하는 일이 같아야 하기 때문이다.
- **컨테이너를 쓰지 않는다.** 산출물은 jar 하나이고 systemd 가 관리한다.
  Docker 는 로컬 개발용 `docker-compose.yml`(MySQL/Redis)에만 남아 있다.
  근거는 `docs/CICD.md` 의 "왜 앱 배포에 Docker 를 쓰지 않는가" 절 참고.
