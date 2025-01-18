<script lang="ts" setup>
import type { CSSProperties } from "vue";
import { computed, ref, onMounted, watch, nextTick } from "vue";
import type { Theme } from "./interface";

const props = withDefaults(
  defineProps<{
    theme?: Theme;
    rounded?: boolean;
    styles?: CSSProperties;
  }>(),
  {
    theme: "default",
    rounded: false,
    styles: () => {
      return {};
    },
  }
);

const textContent = ref("");
const contentStyle = ref({});

const classes = computed(() => {
  return [`tag-${props.theme}`, { "tag-rounded": props.rounded }];
});

onMounted(() => {
  nextTick(() => {
    const slotElement = document.querySelector(".tag-content");
    if (slotElement) {
      textContent.value = slotElement.textContent?.trim() || "";
    }
  });
});

watch(
  () => textContent.value,
  (newValue) => {
    contentStyle.value = newValue.toLowerCase().includes("vip")
      ? { backgroundColor: "#FDDC69", color: "#6A4C1C" } // 深一点的黄色背景，深棕色文字
      : { backgroundColor: "#A5D6A7", color: "#255D27" }; // 深一点的绿色背景，深绿色文字
  }
);
</script>

<template>
  <div :class="classes" :style="styles" class="tag-wrapper">
    <div v-if="$slots.leftIcon" class="tag-left-icon">
      <slot name="leftIcon" />
    </div>
    <span class="tag-content" :style="contentStyle">
      <slot />
    </span>
    <div v-if="$slots.rightIcon" class="tag-right-icon">
      <slot name="rightIcon" />
    </div>
  </div>
</template>

<style lang="scss">
.tag-wrapper {
  @apply rounded-base
  inline-flex
  flex-shrink-0
  flex-wrap
  box-border
  cursor-pointer
  text-center
  items-center
  justify-center
  w-auto
  align-middle
  h-5
  text-xs
  border
  border-solid
  px-1;

  &.tag-default {
    border: 1px solid #d9d9d9;
  }

  &.tag-primary {
    @apply text-white
    bg-primary
    border-primary;
  }

  &.tag-secondary {
    @apply text-white
    bg-secondary
    border-secondary;
  }

  &.tag-danger {
    background: #d71d1d;
    border: 1px solid #d71d1d;
    @apply text-white;
  }

  &.tag-rounded {
    @apply rounded-full;
  }

  .tag-content {
    @apply px-1;
  }
}
</style>
