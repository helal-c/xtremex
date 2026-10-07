export function normalizeId(value: unknown): string {
  if (typeof value !== 'string' || value.length > 128) throw new Error('Invalid user ID');
  const id = value.trim().toLowerCase();
  if (!/^[a-z0-9_.-]{3,32}$/.test(id)) throw new Error('User ID must be 3–32 ASCII letters, numbers, dots, hyphens or underscores');
  return id;
}
