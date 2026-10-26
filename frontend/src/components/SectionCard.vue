<template>
  <div class="card" :style="bodyStyle ? { marginBottom: '14px' } : null">
    <div v-if="title || $slots.extra" class="card-head">
      <div class="card-title">{{ title }}</div>
      <div class="card-extra">
        <slot name="extra" />
      </div>
    </div>
    <div :class="noPad ? '' : 'card-pad'">
      <slot />
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: String,
  noPad: { type: Boolean, default: false },
  bodyStyle: { type: Boolean, default: true }
})
</script>
