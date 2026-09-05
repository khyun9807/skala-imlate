# 서비스 종료 절차

**마지막 운영일 2026-09-07(월) · 중지 2026-09-08(화)**

종료 사유는 두 가지다. 운영자가 SKALA 교육과정을 떠나 더 이상 곁에서 살필 수 없게 되었고,
인프라·외부 API 비용을 개인이 계속 부담하기 어려워졌다.

> 이 문서는 **9/8 이후에 여는 문서**다. 그 전에는 서비스가 평소대로 돌아야 한다.
> 안내 문구 변경은 이 문서가 아니라 `frontend/src/config/serviceEnd.ts` 를 고친다.

---

## 0. 왜 `terraform destroy` 하나로 끝나지 않는가

이게 이 문서에서 가장 중요한 내용이다. destroy 만 돌리고 끝내면 **비용이 계속 나간다.**
2026-09-05 에 실제 계정을 훑어 확인한 결과다.

| 남는 것 | 왜 destroy 가 못 지우나 | 비용 |
|---|---|---|
| **도메인 자동 갱신** | terraform 이 registrar 를 관리하지 않는다 | 2027-08-05 만료일에 연 단위 재청구 |
| **Route53 호스팅 영역** | `main.tf:297` 이 `data "aws_route53_zone"` 으로 **조회만** 한다. destroy 는 A 레코드만 지운다 | 월 $0.50 |
| **S3 아티팩트 버킷** | `imlate-artifacts-550045800654` 는 terraform 리소스가 아니다(배포 과정에서 생성) | 소액, 계속 |
| **CloudWatch 로그 그룹** | 보존기간이 `None` 이라 영구 보관된다 | 소액, 계속 |
| **수동 RDS 스냅샷** | DB 를 지워도 manual 스냅샷은 남는다 | 20GB × 개수 |

`infra/scripts/teardown.sh` 는 정확히 이 목록을 메우려고 만든 것이다.

## 1. 실행

```bash
# 먼저 무엇을 지울지 눈으로 본다 (아무것도 바꾸지 않는다)
infra/scripts/teardown.sh
```

```bash
# 실제 실행 — 'destroy imlate' 를 직접 타이핑해야 진행된다
infra/scripts/teardown.sh --execute
```

스크립트가 하는 일과 그 순서:

1. **도메인 자동 갱신 해제** — 가장 먼저 한다. 뒤가 무엇이 실패하든 "내년에 또 청구되는 것"만은 막는다.
2. **RDS 삭제 방지 해제 + 최종 스냅샷 없음** — `terraform.tfvars` 를 고쳐 `apply` 한다.
   `db_deletion_protection = true` 인 채로는 destroy 가 거부된다.
3. **`terraform destroy`** — EC2·RDS·ElastiCache·SG·VPC·알람.
4. **Route53 호스팅 영역 삭제** — NS/SOA 를 뺀 레코드를 먼저 지워야 영역이 지워진다.
5. **S3 버킷 비우고 삭제.**
6. **CloudWatch 로그 그룹 삭제.**
7. **imlate 이외의 과금 자원 정리** — 수동 RDS 스냅샷 → AMI 등록 해제 → EBS 스냅샷 (아래 §3).

## 2. 최종 스냅샷을 남기지 않는 이유

화면 공지와 사감 발송 문자·메일에 **"명단과 개인정보는 서비스 종료와 함께 모두 지워집니다"** 라고 밝혔다.
최종 스냅샷을 남기면 교육생 실명·반·호수가 담긴 20GB 가 계정에 그대로 남는다.
공지한 대로 하지 않으면 **약속을 어기는 것이고 보관 비용도 계속 든다.**

그래서 `db_skip_final_snapshot = true` 로 바꾼 뒤 destroy 한다.
**명단을 남기고 싶다면 종료 전에 판단해야 한다** — 남긴다면 공지 문구부터 고쳐야 한다.

## 3. imlate 이외의 과금 자원도 함께 지운다

계정 점검에서 이 프로젝트 소유가 아닌 자원도 발견했다. imlate 만 지우면 계정 청구가 0 이 되지 않으므로
**운영자 판단에 따라 스크립트가 함께 지운다**(§7).

| 자원 | 단서 |
|---|---|
| `payperv2-db-snapshot` (수동 RDS 스냅샷, 20GB, 2026-03-24) | 이름이 imlate 와 무관하다 |
| `ami-010f91c023b791182` (`monitoring-server-image`) | 현재 앱 AMI(`ami-00f6db7984ad32b20`)와 다르다 |
| `snap-0eea2f467f6b06d3b` (8GB EBS) | 위 AMI 에 딸린 스냅샷. 인스턴스 `i-02f782d76e4929357` 은 현재 앱(`i-01f4f0f868ca4c55a`)이 아니다 |

삭제 순서에 이유가 있다 — **AMI 를 먼저 등록 해제해야 그 AMI 가 참조하던 EBS 스냅샷을 지울 수 있다.**
반대로 하면 `InvalidSnapshot.InUse` 로 거부된다.

> 이 자원들이 정말 필요 없는지는 --execute 전에 dry-run 출력으로 한 번 더 확인할 것.
> 지우고 나면 되돌릴 수 없다.

## 4. 종료 후 확인

정리 직후가 아니라 **며칠 뒤에** 본다. AWS 청구는 하루 이틀 늦게 반영된다.

```bash
aws ce get-cost-and-usage --time-period Start=2026-09-08,End=2026-09-15 --granularity DAILY --metrics UnblendedCost --region us-east-1
```

- `https://skala-imlate.link` 가 응답하지 않는지 (DNS 캐시 때문에 잠시 남을 수 있다)
- 잔여 자원이 없는지: EC2 / RDS / ElastiCache / EIP / S3 / 호스팅 영역

## 5. AWS 밖에서 따로 끊어야 하는 것

terraform 도 이 스크립트도 손대지 못하는 영역이다.

- **알리고(문자)** — 선불 잔액. 자동 충전을 걸어 두었다면 해제한다. 남은 잔액은 환불 정책 확인.
- **SES** — 발송이 없으면 과금되지 않는다. 아이덴티티는 남겨도 무방하다.
- **GitHub Actions** — 배포 워크플로가 더 이상 돌 곳이 없다. `deploy.yml` 을 비활성화하거나
  리포지터리를 아카이브한다. 그대로 두면 main 에 푸시할 때마다 실패 알림이 온다.

## 6. 되돌리려면

`terraform apply` 로 인프라는 다시 세울 수 있다. **다만 되돌아오지 않는 것이 있다.**

- 등록 데이터(최종 스냅샷을 남기지 않았으므로 완전 소실)
- EIP 주소 — 새 주소를 받게 되고, **알리고 발신 IP 화이트리스트를 다시 등록해야 한다**
  (`terraform output -raw aligo_whitelist_ip`). 이걸 놓치면 문자만 조용히 실패한다.
- Route53 호스팅 영역 — 새로 만들면 NS 레코드가 바뀌므로 registrar 쪽 네임서버도 갱신해야 한다.
