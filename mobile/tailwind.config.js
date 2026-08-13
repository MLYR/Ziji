/** @type {import('tailwindcss').Config} */
module.exports = {
  // 仅扫描移动端源码，避免 workspace 其他工程进入 NativeWind 编译范围。
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        canvas: { light: '#F7F7F9', dark: '#111116' },
        surface: { light: '#FFFFFF', dark: '#1B1A22' },
        ink: { light: '#202027', dark: '#F8F7FA' },
        muted: { light: '#676570', dark: '#AAA6B4' },
        accent: '#F39A39',
      },
    },
  },
  plugins: [],
};
