<script setup lang="ts">
/** 앱 셸. 본문 바로가기 링크와 공통 푸터만 담당한다. */
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <a class="skip-link no-print" href="#main">본문 바로가기</a>
    <RouterView />
    <footer class="app-footer no-print">
      <div class="container">
        <p class="app-footer__text">SKALA 기숙사 야간 복귀 등록 시스템</p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.app-footer {
  padding-block: var(--space-5);
  border-top: var(--border-width) solid var(--c-border);
  background: var(--c-surface);
}

.app-footer__text {
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  text-align: center;
}
</style>
