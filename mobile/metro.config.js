const { getDefaultConfig } = require('expo/metro-config');
const { withNativeWind } = require('nativewind/metro');

// 复用 Expo 默认解析策略，只附加 NativeWind 的 CSS 输入转换。
const config = getDefaultConfig(__dirname);

module.exports = withNativeWind(config, { input: './global.css' });
