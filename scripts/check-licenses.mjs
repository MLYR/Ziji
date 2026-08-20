#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, unlinkSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import process from 'node:process';

const rootDir = new URL('..', import.meta.url).pathname;
const policy = JSON.parse(readFileSync(join(rootDir, 'security/license-policy.json'), 'utf8'));
const classpathFile = join('/tmp', `ziji-license-classpath-${process.pid}.txt`);

function run(command, args, options = {}) {
  try {
    return execFileSync(command, args, {
      cwd: rootDir,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      ...options,
    });
  } catch (error) {
    const details = error.stderr?.toString().trim();
    throw new Error(details ? `${command} ${args[0]}：${details}` : `${command} ${args[0]} 失败`);
  }
}

function normalize(value) {
  return value.replace(/\s+/g, ' ').trim().toLowerCase();
}

function nodePackageKey(name, version) {
  return `${name}@${version}`;
}

function verifyNodeLicenses() {
  const report = JSON.parse(run('pnpm', ['licenses', 'list', '--prod', '--json']));
  const failures = [];

  for (const [license, packages] of Object.entries(report)) {
    for (const dependency of packages) {
      for (const version of dependency.versions) {
        const key = nodePackageKey(dependency.name, version);
        const exception = policy.nodePackageExceptions[key];
        const permitted = policy.nodeAllowedLicenses.includes(license)
          || exception?.license === license;
        if (!permitted) {
          failures.push(`${key}：未批准的许可证 ${license}`);
        }
      }
    }
  }

  if (failures.length > 0) {
    throw new Error(`Node 生产依赖许可证策略失败：\n${failures.join('\n')}`);
  }

  const packageCount = Object.values(report).reduce((total, packages) => total + packages.length, 0);
  console.log(`Node 生产依赖许可证盘点通过（${packageCount} 个包记录）。`);
}

function extractTag(text, tagName) {
  const match = text.match(new RegExp(`<${tagName}>([\\s\\S]*?)</${tagName}>`, 'i'));
  return match?.[1]?.trim();
}

function parentPomPath(pomText, pomPath) {
  const parent = pomText.match(/<parent>([\s\S]*?)<\/parent>/i)?.[1];
  if (!parent) return undefined;

  const groupId = extractTag(parent, 'groupId');
  const artifactId = extractTag(parent, 'artifactId');
  const version = extractTag(parent, 'version');
  if (!groupId || !artifactId || !version || version.includes('${')) return undefined;

  // Maven 本地仓库的 groupId 深度不固定，必须以 repository 根目录而非父目录层数定位。
  const repositoryMarker = '/repository/';
  const markerIndex = pomPath.indexOf(repositoryMarker);
  if (markerIndex < 0) return undefined;
  const repositoryRoot = pomPath.slice(0, markerIndex + repositoryMarker.length - 1);
  return join(repositoryRoot, groupId.replaceAll('.', '/'), artifactId, version, `${artifactId}-${version}.pom`);
}

function readMavenLicenses(pomPath, visited = new Set()) {
  if (!existsSync(pomPath) || visited.has(pomPath)) return [];
  visited.add(pomPath);

  const pomText = readFileSync(pomPath, 'utf8');
  const licensesBlock = pomText.match(/<licenses>([\s\S]*?)<\/licenses>/i)?.[1];
  if (licensesBlock) {
    const names = [...licensesBlock.matchAll(/<(?:name|url)>([\s\S]*?)<\/(?:name|url)>/gi)]
      .map((match) => match[1].replace(/<[^>]+>/g, '').trim())
      .filter(Boolean);
    if (names.length > 0) return names;
  }

  const parentPath = parentPomPath(pomText, pomPath);
  return parentPath ? readMavenLicenses(parentPath, visited) : [];
}

function verifyMavenLicenses() {
  try {
    run('./mvnw', [
      '--batch-mode',
      '--no-transfer-progress',
      'dependency:build-classpath',
      '-Dmdep.includeScope=runtime',
      '-Dmdep.outputAbsoluteArtifactFilename=true',
      `-Dmdep.outputFile=${classpathFile}`,
    ], { cwd: join(rootDir, 'backend') });

    const classpath = readFileSync(classpathFile, 'utf8').trim();
    const jars = classpath ? classpath.split(':') : [];
    const failures = [];

    for (const jarPath of jars) {
      const pomPath = join(dirname(jarPath), `${basename(jarPath, '.jar')}.pom`);
      const licenses = readMavenLicenses(pomPath);
      const permitted = licenses.some((license) => policy.mavenAllowedLicenseTokens
        .some((token) => normalize(license).includes(token)));
      if (!permitted) {
        failures.push(`${basename(jarPath)}：${licenses.length ? licenses.join(' | ') : '缺失许可证元数据'}`);
      }
    }

    if (failures.length > 0) {
      throw new Error(`Maven 运行时依赖许可证策略失败：\n${failures.join('\n')}`);
    }

    console.log(`Maven 运行时依赖许可证盘点通过（${jars.length} 个 JAR）。`);
  } finally {
    if (existsSync(classpathFile)) unlinkSync(classpathFile);
  }
}

function verifyMobileLicense() {
  const mobileLicense = readFileSync(join(rootDir, 'mobile/LICENSE'), 'utf8');
  if (!mobileLicense.includes('The MIT License (MIT)')) {
    throw new Error('mobile/LICENSE 未声明已盘点的 MIT 许可。');
  }
  console.log('mobile/LICENSE 已确认为 MIT。');
}

try {
  verifyNodeLicenses();
  verifyMavenLicenses();
  verifyMobileLicense();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
