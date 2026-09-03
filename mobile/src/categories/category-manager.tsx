import { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';

import type { Category, Tag } from '@/api/api-client';
import type { MobileCategoryApiClient } from '@/api/api-client';

interface CategoryManagerProps {
  api: MobileCategoryApiClient;
  ids: { category: string; tag: string };
}

function categoryEtag(version: number): string {
  return `"${version}"`;
}

/** 分类与标签基础管理：两级分类、停用/合并与标签维护；账户分类作用域由账户 ID 表达。 */
export function CategoryManager({ api, ids }: CategoryManagerProps) {
  const [tab, setTab] = useState<'categories' | 'tags'>('categories');
  const [categories, setCategories] = useState<Category[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [name, setName] = useState('');
  const [categoryType, setCategoryType] = useState<'EXPENSE' | 'INCOME'>('EXPENSE');
  const [parentId, setParentId] = useState('');
  const [accountId, setAccountId] = useState('');
  const [mergeTargets, setMergeTargets] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function reload() {
    try {
      const [categoryEnvelope, tagEnvelope] = await Promise.all([api.listCategories('PERSONAL'), api.listTags()]);
      setCategories(categoryEnvelope.data);
      setTags(tagEnvelope.data);
    } catch {
      setMessage('无法加载分类与标签。');
    }
  }

  useEffect(() => {
    void reload();
  }, []);

  async function createCategory() {
    setSubmitting(true);
    setMessage(null);
    try {
      await api.createCategory(ids.category, {
        name: name.trim(),
        categoryType,
        parentId: parentId === '' ? null : parentId,
        accountId: accountId.trim() === '' ? null : accountId.trim(),
      });
      setName('');
      setParentId('');
      setAccountId('');
      await reload();
    } catch {
      setMessage('创建失败：名称重复或输入不合法。');
    } finally {
      setSubmitting(false);
    }
  }

  async function toggleCategory(target: Category, status: 'ACTIVE' | 'INACTIVE') {
    setMessage(null);
    try {
      await api.patchCategory(target.id, categoryEtag(target.version), { status });
      await reload();
    } catch {
      setMessage('状态更新失败，请刷新重试。');
    }
  }

  async function mergeCategory(source: Category) {
    const target = mergeTargets[source.id];
    if (!target) {
      setMessage('请先选择合并目标分类。');
      return;
    }
    setMessage(null);
    try {
      await api.mergeCategory(source.id, categoryEtag(source.version), ids.category, target);
      await reload();
    } catch {
      setMessage('合并失败：目标必须同类型、同作用域且 ACTIVE。');
    }
  }

  async function createTag() {
    setSubmitting(true);
    setMessage(null);
    try {
      await api.createTag(ids.tag, { name: name.trim() });
      setName('');
      await reload();
    } catch {
      setMessage('标签创建失败。');
    } finally {
      setSubmitting(false);
    }
  }

  async function toggleTag(target: Tag, status: 'ACTIVE' | 'INACTIVE') {
    setMessage(null);
    try {
      await api.patchTag(target.id, categoryEtag(target.version), { status });
      await reload();
    } catch {
      setMessage('标签状态更新失败。');
    }
  }

  const roots = categories.filter((category) => category.status === 'ACTIVE' && category.parentId === null);

  return (
    <ScrollView contentContainerStyle={{ gap: 16 }} testID="category-manager">
      <View className="flex-row gap-2">
        {(['categories', 'tags'] as const).map((option) => (
          <Pressable
            key={option}
            accessibilityRole="button"
            accessibilityState={{ selected: tab === option }}
            onPress={() => setTab(option)}
            testID={`category-manager-tab-${option}`}
            className={`flex-1 min-h-11 items-center justify-center rounded-lg border ${tab === option ? 'border-accent bg-accent' : 'border-accent/40'}`}
          >
            <Text className={`font-semibold ${tab === option ? 'text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}`}>
              {option === 'categories' ? '分类' : '标签'}
            </Text>
          </Pressable>
        ))}
      </View>

      {tab === 'categories' ? (
        <View className="gap-4">
          <View className="gap-3 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
            <Text className="text-base font-bold text-ink-light dark:text-ink-dark">新建分类</Text>
            <TextInput value={name} onChangeText={setName} placeholder="名称" testID="category-new-name"
              className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
            <View className="flex-row gap-2">
              {(['EXPENSE', 'INCOME'] as const).map((option) => (
                <Pressable key={option} accessibilityRole="button" accessibilityState={{ selected: categoryType === option }}
                  onPress={() => setCategoryType(option)}
                  testID={`category-new-type-${option}`}
                  className={`flex-1 min-h-11 items-center justify-center rounded-lg border ${categoryType === option ? 'border-accent bg-accent' : 'border-accent/40'}`}>
                  <Text className={categoryType === option ? 'text-canvas-dark' : 'text-ink-light dark:text-ink-dark'}>{option === 'EXPENSE' ? '支出' : '收入'}</Text>
                </Pressable>
              ))}
            </View>
            <View className="gap-1">
              <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">父分类（可选，先选一级再填名称创建子级）</Text>
              <TextInput value={parentId} onChangeText={setParentId} placeholder="父分类 ID" testID="category-new-parent"
                className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
            </View>
            <TextInput value={accountId} onChangeText={setAccountId} placeholder="账户 ID（留空为个人分类）" testID="category-new-account"
              className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
            <Pressable accessibilityRole="button" accessibilityState={{ disabled: submitting || name.trim() === '' }}
              disabled={submitting || name.trim() === ''} onPress={() => void createCategory()} testID="category-new-submit"
              className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting || name.trim() === '' ? 'opacity-50' : 'active:opacity-70'}`}>
              <Text className="font-bold text-canvas-dark">创建分类</Text>
            </Pressable>
          </View>

          <View className="gap-2">
            <Text className="text-base font-bold text-ink-light dark:text-ink-dark">分类列表</Text>
            {categories.length === 0 ? (
              <Text className="text-muted-light dark:text-muted-dark">暂无分类。</Text>
            ) : categories.map((category) => {
              const parent = categories.find((candidate) => candidate.id === category.parentId);
              const mergeCandidates = categories.filter((candidate) =>
                candidate.id !== category.id && candidate.status === 'ACTIVE' && candidate.categoryType === category.categoryType && candidate.parentId === null);
              return (
                <View key={category.id} className="gap-2 rounded-lg border border-accent/20 p-3" testID={`category-row-${category.id}`}>
                  <View className="flex-row items-center justify-between">
                    <Text className="font-medium text-ink-light dark:text-ink-dark">
                      {parent ? `${parent.name} / ` : ''}{category.name}
                    </Text>
                    <Text className="text-xs text-muted-light dark:text-muted-dark">
                      {category.categoryType === 'EXPENSE' ? '支出' : '收入'} · {category.status === 'ACTIVE' ? '启用' : category.status === 'INACTIVE' ? '停用' : '已合并'}
                    </Text>
                  </View>
                  <View className="flex-row gap-2">
                    {category.status === 'ACTIVE' ? (
                      <Pressable accessibilityRole="button" onPress={() => void toggleCategory(category, 'INACTIVE')}
                        testID={`category-toggle-${category.id}`}
                        className="min-h-9 flex-1 items-center justify-center rounded-lg border border-accent/40">
                        <Text className="text-sm text-ink-light dark:text-ink-dark">停用</Text>
                      </Pressable>
                    ) : category.status === 'INACTIVE' ? (
                      <Pressable accessibilityRole="button" onPress={() => void toggleCategory(category, 'ACTIVE')}
                        testID={`category-toggle-${category.id}`}
                        className="min-h-9 flex-1 items-center justify-center rounded-lg border border-accent/40">
                        <Text className="text-sm text-ink-light dark:text-ink-dark">启用</Text>
                      </Pressable>
                    ) : null}
                    {category.status !== 'MERGED' ? (
                      <Pressable accessibilityRole="button" onPress={() => void mergeCategory(category)}
                        testID={`category-merge-${category.id}`}
                        className="min-h-9 flex-1 items-center justify-center rounded-lg border border-accent/40">
                        <Text className="text-sm text-ink-light dark:text-ink-dark">合并</Text>
                      </Pressable>
                    ) : null}
                  </View>
                  {category.status !== 'MERGED' ? (
                    <View className="flex-row gap-2">
                      <TextInput value={mergeTargets[category.id] ?? ''} onChangeText={(value) => setMergeTargets((previous) => ({ ...previous, [category.id]: value }))}
                        placeholder="合并目标分类 ID" testID={`category-merge-target-${category.id}`}
                        className="min-h-9 flex-1 rounded-lg border border-accent/30 px-3 text-sm text-ink-light dark:text-ink-dark" />
                      <Text className="text-xs text-muted-light dark:text-muted-dark self-center">同级 ACTIVE 分类</Text>
                    </View>
                  ) : null}
                </View>
              );
            })}
          </View>
        </View>
      ) : (
        <View className="gap-4">
          <View className="gap-3 rounded-xl bg-surface-light p-4 dark:bg-surface-dark">
            <Text className="text-base font-bold text-ink-light dark:text-ink-dark">新建标签</Text>
            <TextInput value={name} onChangeText={setName} placeholder="标签名称" testID="tag-new-name"
              className="min-h-11 rounded-lg border border-accent/30 px-3 text-ink-light dark:text-ink-dark" />
            <Pressable accessibilityRole="button" accessibilityState={{ disabled: submitting || name.trim() === '' }}
              disabled={submitting || name.trim() === ''} onPress={() => void createTag()} testID="tag-new-submit"
              className={`min-h-11 items-center justify-center rounded-lg bg-accent ${submitting || name.trim() === '' ? 'opacity-50' : 'active:opacity-70'}`}>
              <Text className="font-bold text-canvas-dark">创建标签</Text>
            </Pressable>
          </View>
          <View className="gap-2">
            <Text className="text-base font-bold text-ink-light dark:text-ink-dark">标签列表</Text>
            {tags.length === 0 ? (
              <Text className="text-muted-light dark:text-muted-dark">暂无标签。</Text>
            ) : tags.map((tag) => (
              <View key={tag.id} className="flex-row items-center justify-between rounded-lg border border-accent/20 p-3">
                <Text className="font-medium text-ink-light dark:text-ink-dark">{tag.name}</Text>
                {tag.status === 'ACTIVE' ? (
                  <Pressable accessibilityRole="button" onPress={() => void toggleTag(tag, 'INACTIVE')} testID={`tag-toggle-${tag.id}`}
                    className="min-h-9 items-center justify-center rounded-lg border border-accent/40 px-3">
                    <Text className="text-sm text-ink-light dark:text-ink-dark">停用</Text>
                  </Pressable>
                ) : (
                  <Pressable accessibilityRole="button" onPress={() => void toggleTag(tag, 'ACTIVE')} testID={`tag-toggle-${tag.id}`}
                    className="min-h-9 items-center justify-center rounded-lg border border-accent/40 px-3">
                    <Text className="text-sm text-ink-light dark:text-ink-dark">启用</Text>
                  </Pressable>
                )}
              </View>
            ))}
          </View>
        </View>
      )}

      {message ? (
        <Text accessibilityRole="alert" testID="category-manager-message" className="text-sm text-destructive">{message}</Text>
      ) : null}
    </ScrollView>
  );
}
