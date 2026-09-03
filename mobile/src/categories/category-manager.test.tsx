import { render, userEvent, waitFor } from '@testing-library/react-native';

import { CategoryManager } from '@/categories/category-manager';
import type { MobileCategoryApiClient } from '@/api/api-client';

const categoryId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a1';
const tagId = '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0a2';

const categories = [
  { id: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0b1', categoryType: 'EXPENSE', name: '餐饮', parentId: null, status: 'ACTIVE', mergedIntoId: null, version: 1 },
  { id: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0b2', categoryType: 'EXPENSE', name: '交通', parentId: null, status: 'INACTIVE', mergedIntoId: null, version: 1 },
];

const tags = [
  { id: '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0c1', name: '工作', status: 'ACTIVE', version: 1 },
];

function mockApi(): MobileCategoryApiClient {
  const api = {
    listCategories: jest.fn().mockResolvedValue({ data: categories, meta: { requestId: 'req-1', hasMore: false } }),
    listTags: jest.fn().mockResolvedValue({ data: tags, meta: { requestId: 'req-2', hasMore: false } }),
    createCategory: jest.fn().mockResolvedValue({ data: categories[0] }),
    patchCategory: jest.fn().mockResolvedValue({ data: { ...categories[0], status: 'INACTIVE' } }),
    mergeCategory: jest.fn().mockResolvedValue({ data: categories[0] }),
    createTag: jest.fn().mockResolvedValue({ data: tags[0] }),
    patchTag: jest.fn().mockResolvedValue({ data: { ...tags[0], status: 'INACTIVE' } }),
  };
  return api as unknown as MobileCategoryApiClient;
}

describe('Mobile 分类与标签管理', () => {
  it('加载并渲染分类列表与状态', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    const view = await render(<CategoryManager api={api} ids={{ category: categoryId, tag: tagId }} />);
    await waitFor(() => expect(view.getByText('餐饮')).toBeTruthy());
    expect(view.getByText('交通')).toBeTruthy();
    expect(api.listCategories).toHaveBeenCalledWith('PERSONAL');
  });

  it('创建支出分类并刷新列表', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    const view = await render(<CategoryManager api={api} ids={{ category: categoryId, tag: tagId }} />);
    await waitFor(() => expect(view.getByText('餐饮')).toBeTruthy());
    await user.type(view.getByTestId('category-new-name'), '零食');
    await user.press(view.getByTestId('category-new-submit'));
    await waitFor(() => expect(api.createCategory).toHaveBeenCalledTimes(1));
    expect(api.createCategory).toHaveBeenCalledWith(categoryId, {
      name: '零食', categoryType: 'EXPENSE', parentId: null, accountId: null,
    });
  });

  it('停用分类提交 PATCH 且携带 If-Match', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    const view = await render(<CategoryManager api={api} ids={{ category: categoryId, tag: tagId }} />);
    await waitFor(() => expect(view.getByText('餐饮')).toBeTruthy());
    await user.press(view.getByTestId('category-toggle-0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0b1'));
    await waitFor(() => expect(api.patchCategory).toHaveBeenCalledWith(
      '0191c1a1-7c2a-7f21-b5ad-6a4c8f19f0b1', '"1"', { status: 'INACTIVE' }));
  });

  it('标签页创建标签', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    const view = await render(<CategoryManager api={api} ids={{ category: categoryId, tag: tagId }} />);
    await waitFor(() => expect(view.getByText('餐饮')).toBeTruthy());
    await user.press(view.getByTestId('category-manager-tab-tags'));
    await waitFor(() => expect(view.getByText('工作')).toBeTruthy());
    await user.type(view.getByTestId('tag-new-name'), '居家');
    await user.press(view.getByTestId('tag-new-submit'));
    await waitFor(() => expect(api.createTag).toHaveBeenCalledWith(tagId, { name: '居家' }));
  });
});
