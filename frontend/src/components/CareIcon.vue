<template>
  <svg class="care-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="paths[name] || paths.order" />
  </svg>
</template>

<script setup>
defineProps({ name: { type: String, default: 'order' } })
const paths = {
  home: 'M3 10 12 3 21 10M5 9v12h14V9M9 21v-7h6v7',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  people: 'M12 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6M6 21v-3a6 6 0 0 1 12 0v3',
  alert: 'M6 8a6 6 0 0 1 12 0c0 7 3 7 3 9H3c0-2 3-2 3-9M9 21h6M12 6v5',
  calendar: 'M3 5h18v16H3zM7 2v6M17 2v6M3 11h18M7 15h3M14 15h3',
  order: 'M5 3h14v18H5zM9 7h6M9 11h6M9 15h3',
  heart: 'M12 21s-9-5.5-9-12a5 5 0 0 1 9-3 5 5 0 0 1 9 3c0 6.5-9 12-9 12M4 12h4l2-4 3 8 2-4h5',
  device: 'M8 2h8a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3M9 5h6M11 18h2',
  clock: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 7v5l4 2',
  arrow: 'M4 12h16m-6-6 6 6-6 6',
  refresh: 'M20 7a9 9 0 1 0 1 8M20 3v5h-5',
  check: 'M5 12 9 16 19 6',
  shield: 'M12 2 20 5v7c0 6-8 10-8 10S4 18 4 12V5zM8 12l3 3 5-6',
  star: 'M12 2 15 8 22 9 17 14 18 21 12 18 6 21 7 14 2 9 9 8z',
  chart: 'M3 3v18h18M7 16v-5M12 16V7M17 16V4',
  location: 'M19 10c0 5-7 12-7 12S5 15 5 10a7 7 0 1 1 14 0M15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  brand: 'M4 12V5a1 1 0 0 1 1-1h7a8 8 0 0 1 8 8v7a1 1 0 0 1-1 1h-7a8 8 0 0 1-8-8M4 12h8V4M12 20v-8h8M8 8h.01M16 16h.01',
  logout: 'M10 4H4v16h6M10 12h11m-4-4 4 4-4 4'
}
</script>
