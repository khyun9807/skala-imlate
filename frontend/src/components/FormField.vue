<script setup lang="ts">
/** 라벨·힌트·오류 안내·datalist 제안을 갖춘 단일 텍스트 입력 필드. */

import { computed, ref } from 'vue'

interface Props {
  /** input id (라벨 for 연결에 사용) */
  id: string
  /** 라벨 문구 */
  label: string
  /** 입력값 */
  modelValue: string
  placeholder?: string
  /** 입력칸 아래 보조 설명 */
  hint?: string
  /** 오류 메시지(있으면 오류 스타일 + aria-invalid) */
  error?: string
  /** datalist 로 제안할 최근 입력값 */
  suggestions?: string[]
  maxlength?: number
  autocomplete?: string
  inputmode?: 'text' | 'numeric' | 'tel' | 'search' | 'none'
  enterkeyhint?: 'enter' | 'done' | 'go' | 'next' | 'search' | 'send'
  disabled?: boolean
  required?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: '',
  hint: '',
  error: '',
  suggestions: () => [],
  maxlength: 20,
  autocomplete: 'off',
  inputmode: 'text',
  enterkeyhint: 'next',
  disabled: false,
  required: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: []
}>()

const inputRef = ref<HTMLInputElement | null>(null)

const hintId = computed(() => `${props.id}-hint`)
const errorId = computed(() => `${props.id}-error`)
const listId = computed(() => `${props.id}-suggestions`)
const hasSuggestions = computed(() => props.suggestions.length > 0)

const describedBy = computed(() => {
  const ids: string[] = []
  if (props.hint) {
    ids.push(hintId.value)
  }
  if (props.error) {
    ids.push(errorId.value)
  }
  return ids.length > 0 ? ids.join(' ') : undefined
})

function onInput(event: Event): void {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.value)
}

/** 첫 오류 필드로 포커스를 옮길 때 사용한다. */
function focus(): void {
  inputRef.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="field">
    <label class="field__label" :for="id">
      {{ label }}
      <span v-if="required" class="field__required" aria-hidden="true">*</span>
    </label>

    <input
      :id="id"
      ref="inputRef"
      class="field__input"
      :class="{ 'field__input--error': !!error }"
      type="text"
      :value="modelValue"
      :list="hasSuggestions ? listId : undefined"
      :placeholder="placeholder"
      :maxlength="maxlength"
      :autocomplete="autocomplete"
      :inputmode="inputmode"
      :enterkeyhint="enterkeyhint"
      :disabled="disabled"
      :required="required"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="describedBy"
      autocapitalize="off"
      autocorrect="off"
      spellcheck="false"
      @input="onInput"
      @blur="emit('blur')"
    />

    <datalist v-if="hasSuggestions" :id="listId">
      <option v-for="option in suggestions" :key="option" :value="option" />
    </datalist>

    <p v-if="hint" :id="hintId" class="field__hint">{{ hint }}</p>

    <!-- 오류 안내: 항상 DOM 에 존재하는 live region 이어야 변경이 안내된다 -->
    <p :id="errorId" class="field__error" aria-live="polite">
      <span v-if="error">{{ error }}</span>
    </p>
  </div>
</template>

<style scoped>
.field {
  display: block;
  min-width: 0;
}

.field__label {
  display: block;
  margin-bottom: var(--space-2);
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--c-text);
}

.field__required {
  color: var(--c-danger-text);
  margin-left: 2px;
}

.field__input {
  display: block;
  width: 100%;
  min-height: var(--tap-min);
  padding: 0.625rem 0.875rem;
  border: var(--border-width) solid var(--c-border-strong);
  border-radius: var(--radius-md);
  background: var(--c-surface);
  color: var(--c-text);
  transition:
    border-color var(--transition-fast),
    box-shadow var(--transition-fast);
}

.field__input::placeholder {
  color: var(--c-text-faint);
  opacity: 1;
}

.field__input:hover:not(:disabled) {
  border-color: var(--c-text-faint);
}

.field__input:focus {
  outline: none;
  border-color: var(--c-focus);
  box-shadow: 0 0 0 3px var(--c-focus-ring);
}

.field__input:focus-visible {
  outline: 2px solid transparent; /* 포커스 링은 box-shadow 로 표현 */
  outline-offset: 0;
}

.field__input:disabled {
  background: var(--c-surface-alt);
  color: var(--c-text-faint);
  cursor: not-allowed;
}

.field__input--error {
  border-color: var(--c-danger-text);
}

.field__hint {
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
}

.field__error {
  margin: 0;
}

.field__error > span {
  display: block;
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-danger-text);
}
</style>
