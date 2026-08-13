jest.mock('expo-sqlite', () => ({ openDatabaseAsync: jest.fn() }));

import type * as SQLite from 'expo-sqlite';

import { LOCAL_DATABASE_SCHEMA_VERSION, migrateLocalDatabase } from './local-database';

describe('local database migration', () => {
  it('只创建同步控制表并记录 schema 版本', async () => {
    const execAsync = jest.fn().mockResolvedValue(undefined);
    const database = { execAsync } as unknown as SQLite.SQLiteDatabase;

    await migrateLocalDatabase(database);

    const sql = execAsync.mock.calls.flat().join('\n');
    expect(sql).toContain('PRAGMA secure_delete = ON');
    expect(sql).toContain('CREATE TABLE IF NOT EXISTS pending_commands');
    expect(sql).toContain(`PRAGMA user_version = ${LOCAL_DATABASE_SCHEMA_VERSION}`);
    expect(sql).not.toContain('ledger_entries');
  });
});
