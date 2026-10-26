<template>
  <div class="stars">
    <el-icon v-for="i in 5" :key="i" :class="i <= Math.round(Number(value)) ? 'on' : 'off'">
      <StarFilled />
    </el-icon>
    <span v-if="showText" class="score">{{ Number(value).toFixed(1) }}</span>
  </div>
</template>

<script setup>
defineProps({
  value: { type: [Number, String], default: 5 },
  showText: { type: Boolean, default: false }
})
</script>

<style scoped>
.stars {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.stars .el-icon {
  font-size: 13px;
}

.on {
  color: #f59e0b;
}

.off {
  color: #e5e7eb;
}

.score {
  margin-left: 5px;
  font-size: 12.5px;
  color: #b45309;
  font-weight: 600;
}
</style>
