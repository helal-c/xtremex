const OFFSET = 6 * 60 * 60 * 1000;
const DAY = 24 * 60 * 60 * 1000;
export function dhakaDay(time: Date): string {
  return new Date(time.getTime() + OFFSET).toISOString().slice(0, 10);
}
export function watchIntervals(previous: Date | null, now: Date, wasPlaying: boolean): { day: string; seconds: number }[] {
  if (!previous || !wasPlaying || now.getTime() <= previous.getTime()) return [];
  let start = Math.max(previous.getTime(), now.getTime() - 60000);
  const result: { day: string; seconds: number }[] = [];
  while (start < now.getTime()) {
    const midnight = (Math.floor((start + OFFSET) / DAY) + 1) * DAY - OFFSET;
    const end = Math.min(midnight, now.getTime());
    result.push({ day: dhakaDay(new Date(start)), seconds: (end - start) / 1000 });
    start = end;
  }
  return result;
}
