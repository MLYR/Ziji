import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';

import type { Category } from '@/api/api-client';

interface CategorySelectProps {
  categories: Category[];
  categoryType: 'EXPENSE' | 'INCOME';
  value: string;
  onChange: (value: string) => void;
  testID: string;
  label: string;
}

/** 分类选择器：一级与子分类均可选；子级以「父 / 子」文本区分，点击选项后收起。 */
export function CategorySelect({ categories, categoryType, value, onChange, testID, label }: CategorySelectProps) {
  const [open, setOpen] = useState(false);
  const selected = categories.find((category) => category.id === value);
  const roots = categories.filter((category) => category.status === 'ACTIVE' && category.categoryType === categoryType);
  const sorted = [...roots]
    .sort((a, b) => (a.parentId === null ? -1 : 1) - (b.parentId === null ? -1 : 1))
    .concat([]);
  const display = selected
    ? (selected.parentId ? `${categories.find((candidate) => candidate.id === selected.parentId)?.name ?? ''} / ` : '') + selected.name
    : '未选择';

  return (
    <View className="gap-1">
      <Text className="text-sm font-semibold text-ink-light dark:text-ink-dark">{label}</Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={label}
        onPress={() => setOpen((previous) => !previous)}
        testID={testID}
        className="min-h-11 justify-center rounded-lg border border-accent/30 px-3"
      >
        <Text className="text-ink-light dark:text-ink-dark">{display}</Text>
      </Pressable>
      {open ? (
        <View className="gap-1 rounded-lg border border-accent/30 p-2" testID={`${testID}-options`}>
          <Pressable
            accessibilityRole="button"
            onPress={() => { onChange(''); setOpen(false); }}
            testID={`${testID}-option-none`}
            className="rounded px-2 py-2"
          >
            <Text className="text-ink-light dark:text-ink-dark">未选择</Text>
          </Pressable>
          {sorted.map((category) => {
            const parent = category.parentId ? categories.find((candidate) => candidate.id === category.parentId) : null;
            const labelText = parent ? `${parent.name} / ${category.name}` : category.name;
            return (
              <Pressable
                key={category.id}
                accessibilityRole="button"
                onPress={() => { onChange(category.id); setOpen(false); }}
                testID={`${testID}-option-${category.id}`}
                className="rounded px-2 py-2"
              >
                <Text className="text-ink-light dark:text-ink-dark">{labelText}</Text>
              </Pressable>
            );
          })}
        </View>
      ) : null}
    </View>
  );
}
