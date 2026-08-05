<script setup lang="ts">
/** 존재하지 않는 주소 안내 화면. 등록 화면으로 돌아가는 링크를 제공한다. */
import { RouterLink } from 'vue-router'
</script>

<template>
  <main id="main" class="app-main">
    <div class="container stack">
      <section class="card notfound">
        <p class="notfound__code">404</p>
        <h1 class="notfound__title">페이지를 찾을 수 없습니다</h1>
        <p class="notfound__desc">
          주소가 잘못되었거나 링크가 만료되었을 수 있습니다. 아래 버튼으로 등록 화면으로 이동해 주세요.
        </p>
        <RouterLink class="btn btn--primary btn--lg" to="/">복귀 등록 화면으로 이동</RouterLink>
      </section>
    </div>
  </main>
</template>

<style scoped>
.notfound {
  text-align: center;
}

.notfound__code {
  font-size: var(--fs-2xl);
  font-weight: 800;
  color: var(--c-text-faint);
  letter-spacing: 0.05em;
}

.notfound__title {
  margin-top: var(--space-2);
  font-size: var(--fs-xl);
  font-weight: 800;
}

.notfound__desc {
  margin-top: var(--space-3);
  margin-bottom: var(--space-5);
  color: var(--c-text-muted);
}
</style>
