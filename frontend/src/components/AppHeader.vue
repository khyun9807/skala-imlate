<script setup lang="ts">
/** 페이지 상단 헤더. 서비스명·제목·날짜와 우측 액션 슬롯을 제공한다. */

interface Props {
  /** 페이지 제목 */
  title: string
  /** 제목 위 작은 라벨 */
  eyebrow?: string
  /** 제목 아래 보조 설명(주로 날짜) */
  subtitle?: string
}

withDefaults(defineProps<Props>(), {
  eyebrow: 'SKALA 기숙사',
  subtitle: '',
})
</script>

<template>
  <header class="app-header">
    <div class="app-header__main">
      <p class="app-header__eyebrow">{{ eyebrow }}</p>
      <h1 class="app-header__title">{{ title }}</h1>
      <p v-if="subtitle" class="app-header__subtitle">{{ subtitle }}</p>
      <div class="app-header__badges">
        <slot name="badges" />
      </div>
    </div>
    <div class="app-header__actions no-print">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.app-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.app-header__main {
  min-width: 0;
  flex: 1 1 260px;
}

.app-header__eyebrow {
  font-size: var(--fs-sm);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--c-text-muted);
}

.app-header__title {
  margin-top: var(--space-1);
  font-size: var(--fs-2xl);
  font-weight: 800;
}

.app-header__subtitle {
  margin-top: var(--space-2);
  font-size: var(--fs-base);
  color: var(--c-text-muted);
}

.app-header__badges:not(:empty) {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.app-header__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

@media (max-width: 600px) {
  .app-header__actions {
    width: 100%;
  }
}
</style>
