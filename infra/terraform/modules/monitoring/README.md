# modules/monitoring — 운영 알람

기숙사 야간복귀 시스템의 **조용한 실패**를 없애기 위한 CloudWatch 알람 묶음이다.

이 도메인에서 발송 실패는 곧 교육생이 기숙사에 못 들어가는 사고다. 운영 타임라인은

```
00:00 등록 시작 → 21:45 마감 → 21:50 사감 발송
  → 22:05 / 22:20 실패분 재시도 → 22:30 문 잠김 → 23:30 일괄 개방
```

이므로 모든 감시의 목표는 하나다. **22:30 전에 사람이 알아채는 것.**

---

## 만드는 것

| 알람 | 지표 | 판정 | missing 처리 |
|---|---|---|---|
| `-ec2-status-check-failed` | `AWS/EC2 StatusCheckFailed` | 1분 × 2회 연속 ≥ 1 | **breaching** |
| `-dispatch-heartbeat-missing` ★ | `Imlate/DispatchCompleted` | 최근 24시간에 0건 | **breaching** |
| `-dispatch-failures` | `Imlate/DispatchFailures` | 5분 합계 > 0 | notBreaching |
| `-rds-free-storage-low` | `AWS/RDS FreeStorageSpace` | 10분 연속 < 2 GiB | missing |
| `-rds-cpu-high` | `AWS/RDS CPUUtilization` | 15분 연속 > 80% | missing |
| `-redis-memory-high-<노드>` | `AWS/ElastiCache DatabaseMemoryUsagePercentage` | 15분 연속 > 80% | missing |
| `-ec2-disk-used-high` | `CWAgent disk_used_percent` | 15분 연속 > 85% | missing |

전부 하나의 SNS 주제(`<prefix>-alerts`)로 모이고, 그 주제는 `alert_email` 하나만 구독한다.

### 일부러 만들지 않은 것

알람이 많으면 아무도 안 본다. 안 보는 알람은 없는 알람이다.

- **EC2 CPU** — t3.small 은 크레딧 기반이라 CPU 100% 자체가 사고가 아니다. 이 앱은 21:50 몇 초를 빼면 사실상 유휴라 크레딧이 마를 일이 없다.
- **메모리** — JVM 이 `-Xmx1024m` 로 고정이고 OOM 이면 systemd 가 재시작한다. 그 재시작이 발송을 놓쳤는지는 하트비트 알람이 잡는다.
- **RDS 연결 수 / 읽기 지연** — 200명 규모에서 임계값을 정할 근거가 없다. 관측 데이터가 쌓인 뒤에 붙일 것.
- **ALB / WAF** — 기본 구성에서 아예 만들어지지 않는다.

---

## ★ 이메일 구독은 apply 만으로 살아나지 않는다

`aws_sns_topic_subscription` 은 구독 요청까지만 만든다. AWS 가 수신 주소로
**"AWS Notification - Subscription Confirmation"** 메일을 보내고,
**수신자가 그 메일의 링크를 눌러야** `PendingConfirmation → Confirmed` 로 바뀐다.

누르기 전까지는 알람이 ALARM 으로 전이해도 **메일이 오지 않는다.**
Terraform 은 확인 여부를 알 수 없으므로 `apply` 성공 = 알림 동작 이 아니다.

```bash
# 구독 상태 확인 (SubscriptionArn 이 "PendingConfirmation" 이면 아직 확인 전)
aws sns list-subscriptions-by-topic \
  --topic-arn "$(terraform output -raw alert_sns_topic_arn)" \
  --query "Subscriptions[].{Endpoint:Endpoint,Status:SubscriptionArn}" --output table

# 실제로 메일이 오는지 테스트
aws sns publish \
  --topic-arn "$(terraform output -raw alert_sns_topic_arn)" \
  --subject "imlate alert test" --message "test"
```

---

## ★ 수신 채널 분리 (사감 ↔ 운영자)

이 모듈은 **사감 연락처를 입력으로 받지 않는다.** `supervisor1_phone` / `supervisor2_email`
같은 변수는 이 모듈 어디에도 등장하지 않으며, 알림은 오직 `alert_email` 한 곳으로만 간다.

- 사감에게 가는 것 = 오늘 야간복귀 명단(문자/메일). 앱이 보낸다.
- `alert_email` 로 가는 것 = "시스템이 고장났다"는 운영자용 신호. CloudWatch 가 보낸다.

두 주소가 우연히 같은 사서함일 수는 있지만(운영자가 발송 결과를 메일로 확인하는 구조),
**설정 경로는 완전히 분리되어 있어야 한다.** `alert_email` 에 사감 개인 주소를 넣지 말 것 —
새벽 3시에 사감이 `RDS CPU 80%` 메일을 받게 된다.

---

## ★ 하트비트 알람 — 숫자의 근거

가장 중요하고 가장 까다로운 알람이다. 앱은 21:50 배치가 끝나면
`Imlate/DispatchCompleted` 를 **하루 한 번** 올린다. 이 알람은 그게 안 온 날을 잡는다.

### 순진하게 만들면 실패한 날에 조용해진다

`period 5분 + evaluation_periods 1 + missing = breaching` 로 잡으면
하루 23시간 55분 동안 데이터가 없으므로 알람은 거의 항상 ALARM 이다.
그런데 CloudWatch 는 **상태가 바뀔 때만** SNS 에 알린다.

- 발송 성공한 날 → 21:50 `ALARM→OK`, 21:55 `OK→ALARM`. 매일 알림 2통. 소음.
- 발송 실패한 날 → 이미 ALARM 이라 **상태 변화 없음 → 알림 없음.**

즉 "실패한 날에만 조용한" 알람이 된다. 없는 것보다 나쁘다.

### 그래서 설계를 뒤집었다 — "최근 24시간에 하트비트가 있었는가"

```
period              = 300   # 5분
evaluation_periods  = 288   # 288 × 5분 = 86,400초 = 24시간
datapoints_to_alarm = 288   # 288개 구간이 전부 비어야 알람
statistic           = Sum,  threshold = 1, LessThanThreshold
treat_missing_data  = breaching
```

최근 24시간에 하트비트가 하나라도 있으면 위반 구간이 287개뿐이라 **OK**.
하나도 없어야 288/288 위반 → **ALARM**.

시간축:

```
어제 21:50 하트비트 → [21:50,21:55) 구간
                       └ 오늘 21:55 에 24시간 창 밖으로 밀려난다
  · 오늘 21:50 발송 성공 → 밀려나기 5분 전에 오늘 것이 들어옴 → OK 유지, 알림 없음
  · 오늘 발송이 안 돎    → 창이 완전히 비는 순간 ALARM 전이
                          → 21:55~22:10 KST 메일 (통금 22:30 까지 20분 이상 여유)
```

### 왜 300 / 288 인가

- CloudWatch 알람의 평가 구간 상한은 24시간이다(`period × evaluation_periods ≤ 86400`).
  하루 한 번 오는 신호를 "정상일 때 OK" 로 유지하려면 창을 **정확히 24시간**으로 꽉 채워야 한다.
  따라서 곱이 정확히 86,400 인 조합만 후보다.
- `period=3600 / N=24` 도 곱이 86,400 이고 판정 시각이 매일 22:00 정각으로 고정된다는 장점이 있다.
  하지만 CloudWatch 는 늦게 도착한 데이터를 보정하려고 평가 구간보다 몇 개 뒤의 데이터포인트까지
  조회한다. 그 "몇 개"가 1시간짜리면 전이가 몇 시간 밀릴 수 있고 **22:30 을 놓친다.**
  `period=300` 이면 같은 지연이 10~15분에 그친다. 늦는 것보다 조금 이른 편이 맞다.
- `period=60` 으로 더 줄이면 `evaluation_periods` 가 1440 이 되어 CloudWatch 상한에 딱 걸린다.
  얻는 것 없이 평가 지연만 는다.

### 한계 (알고 쓸 것)

1. 판정 시각이 "어제 하트비트가 찍힌 시각 + 5분"에 종속된다. 어제 발송이 22:05 재시도에서야
   성공했다면 오늘 판정도 22:10 으로 밀린다.
2. 판정이 22:05 / 22:20 재시도보다 이르다. **의도한 것이다.** `DispatchCompleted` 는
   "21:50 배치가 돌기는 했는가"의 하트비트이지 "전원에게 도착했는가"가 아니다(후자는
   `DispatchFailures` 알람이 본다). 21:55 에 하트비트가 없다 = 배치 자체가 안 돌았다 =
   재시도를 기다릴 게 아니라 지금 손을 써야 한다.
3. **이틀 연속 실패하면 둘째 날에는 새 메일이 오지 않는다.** 이미 ALARM 이라 전이가 없다.
   CloudWatch 알람의 공통 제약이다. 한 번 울리면 반드시 원인을 없애 OK 로 되돌릴 것.
4. "매일 22:25 정각에 판정" 같은 완전히 결정적인 감시는 CloudWatch 알람만으로 **불가능하다.**
   알람에는 시각 개념이 없다(metric math 에도 wall-clock 함수가 없다). 필요하다면
   `EventBridge Scheduler(cron(25 22 * * ? *), Asia/Seoul) → Lambda → GetMetricData 판정 → SNS`
   구조로 가야 한다. Lambda 코드가 필요해 이번 범위에서는 만들지 않았다.

### 앱이 지켜야 하는 계약

```
Namespace  : Imlate
Metric     : DispatchCompleted   Unit=Count  Value>=1   (21:50 배치 종료 시 1회)
Metric     : DispatchFailures    Unit=Count  Value=실패 건수 (실패 없으면 안 올려도 됨)
Dimensions : 없음 (기본값). 앱이 차원을 붙인다면 dispatch_metric_dimensions 도 같이 맞출 것
```

지표를 올리는 것 때문에 발송이 느려지거나 실패해서는 안 된다.
`PutMetricData` 는 발송 트랜잭션 밖에서, 예외를 삼키고, 비동기로 호출할 것.

`enable_dispatch_heartbeat_alarm = false` 로 두면 이 알람만 빠진다.
**앱이 아직 지표를 올리지 않는 상태에서 켜면 만들자마자 ALARM 메일이 한 통 온다**
(최근 24시간 데이터가 통째로 없으므로). 앱 배포 전이라면 false 로 두었다가 나중에 켜는 편이 깔끔하다.

---

## 설치 후 확인

```bash
terraform output alert_subscription_notice     # 구독 확인 절차
terraform output monitoring_alarm_names        # 만들어진 알람 목록

# 모든 알람의 현재 상태 (INSUFFICIENT_DATA 가 남아 있으면 지표가 안 잡히는 것)
aws cloudwatch describe-alarms \
  --alarm-name-prefix "$(terraform output -raw name_prefix 2>/dev/null || echo imlate-prod)" \
  --query "MetricAlarms[].{Name:AlarmName,State:StateValue}" --output table
```

### 디스크 알람이 INSUFFICIENT_DATA 로 남는다면

CloudWatch Agent 의 `disk` 플러그인은 지표에 `path` / `device` / `fstype` 차원을 함께 붙이는데,
`device` 이름이 인스턴스 세대에 따라 다르다(Nitro `nvme0n1p1`, Xen `xvda1`).
그래서 이 모듈은 후보를 나열해 `MAX([m1,m2])` 로 합친다(알람에서는 `SEARCH()` 를 쓸 수 없다).

실제 차원 확인:

```bash
aws cloudwatch list-metrics --namespace CWAgent --metric-name disk_used_percent \
  --query "Metrics[].Dimensions" --output json
```

다르면 `disk_devices` / `disk_fstype` / `disk_path` 를 맞춰 주면 된다.

> 더 깔끔한 해법은 `modules/ec2` 의 CloudWatch Agent 설정에
> `"aggregation_dimensions": [["InstanceId"]]` 를 추가해 device/fstype 차원을 없애는 것이다.
> 그 파일은 이 모듈 소유가 아니라 여기서는 손대지 않았다.
