module.exports = function configureBabel(api) {
  // 缓存固定配置，避免 Metro 为同一进程重复计算 Babel preset。
  api.cache(true);

  return {
    presets: [
      ['babel-preset-expo', { jsxImportSource: 'nativewind' }],
      'nativewind/babel',
    ],
  };
};
