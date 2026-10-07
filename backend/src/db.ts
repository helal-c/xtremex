import pg from 'pg';
import type { PoolClient } from 'pg';

export async function transaction<T>(pool: pg.Pool, work: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await work(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally { client.release(); }
}
let singleton: pg.Pool | undefined;
export function database(): pg.Pool {
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is not configured');
  return singleton ??= new pg.Pool({ connectionString: process.env.DATABASE_URL, max: 3, connectionTimeoutMillis: 10000, idleTimeoutMillis: 10000 });
}
