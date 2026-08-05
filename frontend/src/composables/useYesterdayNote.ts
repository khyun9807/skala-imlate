/**
 * 어제 연장 복귀 인원을 알려 주는 한 줄 안내.
 *
 * 6개 문구 중 하나를 **화면에 들어올 때마다 새로 뽑아** 보여 준다(하루 고정이 아니다).
 * 그래서 같은 사람이 여러 번 들어와도 매번 다른 문장을 만나게 된다.
 *
 * **아무도 없었으면 아무 말도 하지 않는다.** "어제 0명이 …" 는 문장으로 성립하지 않고,
 * 굳이 "어제는 아무도 없었습니다"라고 알릴 이유도 없다. 인원 수를 못 불러왔을 때도 마찬가지로 감춘다 —
 * 이 문구는 부가 정보라서, 없으면 그냥 없는 채로 두는 편이 낫다.
 */

import { onMounted, ref } from 'vue'

import { fetchYesterday } from '../api/client'

/**
 * 인원 수가 들어갈 자리를 `{n}` 으로 둔 문구들.
 *
 * 운영자가 직접 작성한 문장이므로 표현을 임의로 다듬지 않는다.
 */
const TEMPLATES: readonly string[] = [
  '어제 {n}명이 사감님의 호날두 수면법을 도왔습니다.',
  '어제 {n}명이 강의실에 한동안 묶여 있었습니다. 자발적으로요.',
  '어제 {n}명이 강의실과 조금 긴 시간을 함께했습니다.',
  '어제 {n}명이 강의실과 긴 시간을 보낸 뒤 23시 30분에 작별했습니다.',
  '어제 {n}명이 강의실과 자정 직전까지 동행했습니다.',
  '어제 {n}명이 강의실과 조금 긴 하루를 보내고 23시 30분에 헤어졌습니다.',
]

/** 문구 하나를 무작위로 골라 인원 수를 채운다. */
export function pickYesterdayMessage(count: number, random: () => number = Math.random): string {
  const index = Math.min(TEMPLATES.length - 1, Math.max(0, Math.floor(random() * TEMPLATES.length)))
  return TEMPLATES[index].replace('{n}', String(count))
}

/** 화면에서 쓰는 상태. `message` 가 빈 문자열이면 아무것도 그리지 않는다. */
export function useYesterdayNote() {
  const message = ref('')

  onMounted(async () => {
    const result = await fetchYesterday()
    // 못 불러왔거나(null) 아무도 없었으면(0) 조용히 감춘다.
    if (!result || !Number.isFinite(result.count) || result.count <= 0) {
      return
    }
    message.value = pickYesterdayMessage(result.count)
  })

  return { message }
}
