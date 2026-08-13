import * as SQLite from 'expo-sqlite';

const DATABASE_NAME = 'ziji-cache.db';

export const LOCAL_DATABASE_SCHEMA_VERSION = 1;

export interface LocalDatabaseSecurity {
  prepare(database: SQLite.SQLiteDatabase): Promise<void>;
}

export const platformDatabaseSecurity: LocalDatabaseSecurity = {
  async prepare(database) {
    // Expo managed 基座先依赖系统沙箱并启用 secure_delete；生产加密适配器在原生构建门禁替换此实现。
    await database.execAsync('PRAGMA secure_delete = ON;');
  },
};

let databasePromise: Promise<SQLite.SQLiteDatabase> | undefined;

export async function migrateLocalDatabase(database: SQLite.SQLiteDatabase): Promise<void> {
  await platformDatabaseSecurity.prepare(database);

  // SQLite 只保存缓存和同步控制信息，绝不成为余额或交易的权威事实源。
  await database.execAsync(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS sync_state (
      scope TEXT PRIMARY KEY NOT NULL,
      cursor TEXT,
      updated_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS pending_commands (
      id TEXT PRIMARY KEY NOT NULL,
      operation_id TEXT NOT NULL,
      idempotency_key TEXT NOT NULL,
      payload_json TEXT NOT NULL,
      state TEXT NOT NULL CHECK (state IN ('PENDING', 'SENDING', 'REJECTED')),
      created_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sync_conflicts (
      id TEXT PRIMARY KEY NOT NULL,
      command_id TEXT NOT NULL,
      problem_json TEXT NOT NULL,
      created_at TEXT NOT NULL
    );
    PRAGMA user_version = ${LOCAL_DATABASE_SCHEMA_VERSION};
  `);
}

async function openAndMigrateDatabase(): Promise<SQLite.SQLiteDatabase> {
  const database = await SQLite.openDatabaseAsync(DATABASE_NAME);
  await migrateLocalDatabase(database);

  return database;
}

export function getLocalDatabase(): Promise<SQLite.SQLiteDatabase> {
  // 复用同一初始化 Promise，防止并发启动重复执行迁移。
  databasePromise ??= openAndMigrateDatabase();
  return databasePromise;
}
