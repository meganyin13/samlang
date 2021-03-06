import {
  Hashable,
  mapOf,
  setOf,
  hashMapOf,
  hashSetOf,
  listShallowEquals,
  mapEquals,
  hashMapEquals,
  setEquals,
} from '../collections';

it('ReadOnly map and set tests', () => {
  expect(mapOf().size).toBe(0);
  expect(setOf().size).toBe(0);
  expect(
    mapOf([{ uniqueHash: () => 3 }, 3], [{ uniqueHash: () => 4 }, 4], [{ uniqueHash: () => 3 }, 4])
      .size
  ).toBe(2);
  expect(
    setOf({ uniqueHash: () => 3 }, { uniqueHash: () => 4 }, { uniqueHash: () => 3 }).size
  ).toBe(2);
});

class HashableClass implements Hashable {
  constructor(public readonly n: number) {}

  uniqueHash = (): number => this.n;
}

const N = (n: number): HashableClass => new HashableClass(n);

it('map tests', () => {
  const map = hashMapOf([N(1), 1], [N(2), 2]);

  map.forEach(() => {});

  expect(map.get(N(1))).toBe(1);
  expect(map.get(N(2))).toBe(2);
  expect(map.get(N(3))).toBeUndefined();
  expect(map.has(N(1))).toBeTruthy();
  expect(map.has(N(2))).toBeTruthy();
  expect(map.has(N(3))).toBeFalsy();
  expect(map.size).toBe(2);

  map.set(N(1), 3);
  expect(map.get(N(1))).toBe(3);
  expect(map.get(N(2))).toBe(2);
  expect(map.get(N(3))).toBeUndefined();
  expect(map.has(N(1))).toBeTruthy();
  expect(map.has(N(2))).toBeTruthy();
  expect(map.has(N(3))).toBeFalsy();
  expect(map.size).toBe(2);

  map.delete(N(1));
  expect(map.get(N(1))).toBeUndefined();
  expect(map.get(N(2))).toBe(2);
  expect(map.get(N(3))).toBeUndefined();
  expect(map.has(N(1))).toBeFalsy();
  expect(map.has(N(2))).toBeTruthy();
  expect(map.has(N(3))).toBeFalsy();
  expect(map.size).toBe(1);

  map.clear();
  expect(map.get(N(1))).toBeUndefined();
  expect(map.get(N(2))).toBeUndefined();
  expect(map.get(N(3))).toBeUndefined();
  expect(map.has(N(1))).toBeFalsy();
  expect(map.has(N(2))).toBeFalsy();
  expect(map.has(N(3))).toBeFalsy();
  expect(map.size).toBe(0);
  map.forEach(() => {
    throw new Error();
  });
});

it('set tests', () => {
  const set = hashSetOf(N(1), N(2));

  set.forEach(() => {});

  expect(set.has(N(1))).toBeTruthy();
  expect(set.has(N(2))).toBeTruthy();
  expect(set.has(N(3))).toBeFalsy();
  expect(set.size).toBe(2);

  set.add(N(1));
  expect(set.has(N(1))).toBeTruthy();
  expect(set.has(N(2))).toBeTruthy();
  expect(set.has(N(3))).toBeFalsy();
  expect(set.size).toBe(2);

  set.delete(N(1));
  expect(set.has(N(1))).toBeFalsy();
  expect(set.has(N(2))).toBeTruthy();
  expect(set.has(N(3))).toBeFalsy();
  expect(set.size).toBe(1);

  set.clear();
  expect(set.has(N(1))).toBeFalsy();
  expect(set.has(N(2))).toBeFalsy();
  expect(set.has(N(3))).toBeFalsy();
  expect(set.size).toBe(0);
  set.forEach(() => {
    throw new Error();
  });
});

it('listShallowEquals tests', () => {
  expect(listShallowEquals([], [])).toBeTruthy();
  expect(listShallowEquals(['a'], ['a'])).toBeTruthy();
  expect(listShallowEquals(['a', 3], ['a', 3])).toBeTruthy();
  expect(listShallowEquals(['a', 3], ['a', 2])).toBeFalsy();
  expect(listShallowEquals(['a', 3], ['a'])).toBeFalsy();
  expect(listShallowEquals(['a', 3], [])).toBeFalsy();
});

it('mapEquals tests', () => {
  expect(mapEquals(new Map(), new Map([['1', 1]]))).toBeFalsy();
  expect(mapEquals(new Map([['2', 1]]), new Map([['1', 1]]))).toBeFalsy();
  expect(mapEquals(new Map([['1', 1]]), new Map([['1', 1]]))).toBeTruthy();
});

it('hashMapEquals tests', () => {
  expect(hashMapEquals(hashMapOf<HashableClass, number>(), hashMapOf([N(1), 1]))).toBeFalsy();
  expect(hashMapEquals(hashMapOf([N(2), 1]), hashMapOf([N(1), 1]))).toBeFalsy();
  expect(hashMapEquals(hashMapOf([N(1), 1]), hashMapOf([N(1), 1]))).toBeTruthy();
});

it('setEquals tests', () => {
  expect(setEquals(new Set(), new Set([1]))).toBeFalsy();
  expect(setEquals(new Set([2]), new Set([1]))).toBeFalsy();
  expect(setEquals(new Set([1]), new Set([1]))).toBeTruthy();
});
