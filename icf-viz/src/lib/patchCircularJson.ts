if (process.env.NODE_ENV === "development" && typeof window !== "undefined") {
  const _stringify = JSON.stringify;
  JSON.stringify = function (
    value: unknown,
    replacer?: Parameters<typeof JSON.stringify>[1],
    space?: Parameters<typeof JSON.stringify>[2],
  ) {
    try {
      return _stringify.call(JSON, value, replacer as never, space);
    } catch {
      return _stringify.call(JSON, null);
    }
  } as typeof JSON.stringify;
}
