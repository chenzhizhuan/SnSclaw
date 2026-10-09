<template>
  <section class="enterprise-offer" aria-labelledby="enterprise-offer-title">
    <div class="offer-hero">
      <div class="offer-intro">
        <span class="offer-badge"><span aria-hidden="true" />{{ t('enterprise.offer.badge') }}</span>
        <h2 id="enterprise-offer-title">{{ t('enterprise.offer.title') }}</h2>
        <p class="offer-description">{{ t('enterprise.offer.description') }}</p>
      </div>
      <div class="offer-actions">
        <a class="offer-primary" href="https://mate.vip/enterprise/" target="_blank" rel="noopener noreferrer">
          {{ t('enterprise.offer.demo') }}
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg>
        </a>
        <a class="offer-secondary" href="https://mate.vip/enterprise/" target="_blank" rel="noopener noreferrer">{{ t('enterprise.offer.website') }} ↗</a>
      </div>
    </div>

    <div class="offer-capabilities">
      <article v-for="capability in capabilities" :key="capability.key" class="capability">
        <span class="capability-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path :d="capability.icon" /></svg></span>
        <div>
          <h3>{{ t(`enterprise.offer.${capability.key}Title`) }}</h3>
          <p>{{ t(`enterprise.offer.${capability.key}Desc`) }}</p>
        </div>
      </article>
    </div>

    <div class="offer-scenarios">
      <span class="section-label">{{ t('enterprise.offer.scenarioLabel') }}</span>
      <ul>
        <li v-for="scenario in scenarios" :key="scenario">{{ t(`enterprise.offer.${scenario}`) }}</li>
      </ul>
    </div>
    <footer class="offer-footer">
      <p>{{ t('enterprise.offer.scope') }}</p>
      <details>
        <summary>{{ t('enterprise.offer.roadmapLabel') }}</summary>
        <p>{{ t('enterprise.offer.roadmap') }}</p>
      </details>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const capabilities = [
  { key: 'engine', icon: 'M8 3v3m8-3v3M8 18v3m8-3v3M3 8h3m-3 8h3m12-8h3m-3 8h3M6 6h12v12H6zM9 9h6v6H9z' },
  { key: 'governance', icon: 'M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6zM8 12l3 3 5-6' },
  { key: 'knowledge', icon: 'M12 5v16M3 4h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5v15h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3z' },
  { key: 'workflow', icon: 'M9 3h6v5H9zM3 16h6v5H3zM15 16h6v5h-6zM12 8v4M6 16v-4h12v4' },
  { key: 'memory', icon: 'M5 7c0-5 14-5 14 0s-14 5-14 0m0 0v10c0 5 14 5 14 0V7M5 12c0 5 14 5 14 0' },
  { key: 'security', icon: 'M7 10V7a5 5 0 0 1 10 0v3M5 10h14v11H5zM12 14v3' },
] as const
const scenarios = ['scenarioPolicy', 'scenarioBi', 'scenarioContract', 'scenarioTender', 'scenarioOps', 'scenarioDocs'] as const
</script>

<style scoped>
.enterprise-offer {
  flex-shrink: 0;
  overflow: hidden;
  border: 1px solid var(--mc-border-light);
  border-radius: 20px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  box-shadow: 0 4px 24px rgb(0 0 0 / 3%);
}
.offer-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  padding: 28px 30px;
  background: radial-gradient(ellipse at 100% 0%, color-mix(in srgb, var(--mc-primary) 10%, transparent), transparent 65%);
}
.offer-intro { max-width: 700px; min-width: 0; }
.offer-badge { display: inline-flex; align-items: center; gap: 7px; color: var(--mc-primary); font-size: 11px; font-weight: 600; letter-spacing: .06em; }
.offer-badge span { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
h2 { margin: 12px 0 10px; font-size: clamp(22px, 2vw, 28px); letter-spacing: -.035em; line-height: 1.3; font-weight: 700; }
.offer-description { margin: 0; max-width: 660px; font-size: 13px; line-height: 1.8; color: var(--mc-text-secondary); }
.offer-actions { display: flex; flex-direction: column; align-items: center; gap: 14px; flex-shrink: 0; }
a { text-decoration: none; }
.offer-primary { display: inline-flex; align-items: center; justify-content: center; gap: 22px; padding: 12px 20px; border-radius: 10px; background: var(--mc-primary); color: #fff; font-size: 13px; font-weight: 600; box-shadow: 0 4px 12px color-mix(in srgb, var(--mc-primary) 18%, transparent); transition: background .15s; }
.offer-primary:hover { background: var(--mc-primary-hover); }
.offer-primary svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.offer-secondary { color: var(--mc-text-secondary); font-size: 12px; }
.offer-secondary:hover { color: var(--mc-primary); }
a:focus-visible, summary:focus-visible { outline: 2px solid var(--mc-primary); outline-offset: 4px; }
.offer-capabilities { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px 28px; padding: 24px 30px; border-top: 1px solid var(--mc-border-light); }
.capability { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.capability-icon { display: grid; place-items: center; flex-shrink: 0; width: 36px; height: 36px; border: 1px solid color-mix(in srgb, var(--mc-primary) 10%, var(--mc-border-light)); border-radius: 10px; background: color-mix(in srgb, var(--mc-primary) 5%, var(--mc-bg-elevated)); color: var(--mc-primary); }
.capability-icon svg { width: 18px; height: 18px; stroke: currentColor; fill: none; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
h3 { margin: 1px 0 6px; font-size: 13px; font-weight: 600; line-height: 1.5; }
.capability p { margin: 0; font-size: 12px; line-height: 1.75; color: var(--mc-text-secondary); }
.offer-scenarios { display: flex; align-items: baseline; gap: 16px; padding: 18px 30px; border-top: 1px solid var(--mc-border-light); }
.section-label { flex-shrink: 0; font-size: 11px; font-weight: 600; color: var(--mc-text-tertiary); }
ul { display: flex; flex-wrap: wrap; gap: 8px; margin: 0; padding: 0; list-style: none; }
li { padding: 5px 10px; border-radius: 6px; background: var(--mc-bg-muted); color: var(--mc-text-secondary); font-size: 11px; }
.offer-footer { padding: 14px 30px; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; background: var(--mc-bg-muted); }
.offer-footer p { margin: 0; font-size: 11px; line-height: 1.7; color: var(--mc-text-tertiary); }
.offer-footer > p { max-width: 700px; }
details { flex: 0 0 180px; font-size: 11px; color: var(--mc-text-secondary); }
summary { cursor: pointer; line-height: 1.7; }
details[open] p { margin-top: 8px; }
@media (max-width: 1100px) { .offer-capabilities { grid-template-columns: repeat(2, minmax(0, 1fr)); } .offer-hero { gap: 20px; } }
@media (max-width: 640px) {
  .offer-hero { align-items: flex-start; flex-direction: column; padding: 22px 18px; }
  .offer-actions { flex-direction: row; flex-wrap: wrap; }
  .offer-capabilities { grid-template-columns: 1fr; padding: 22px 18px; gap: 20px; }
  .offer-scenarios { flex-direction: column; gap: 10px; padding: 16px 18px; }
  .offer-footer { flex-direction: column; gap: 8px; padding: 14px 18px; }
  details { flex-basis: auto; }
}
</style>
