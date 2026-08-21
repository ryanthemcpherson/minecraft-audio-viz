function normalize(value) {
    return String(value ?? '').trim().toLowerCase();
}

export function filterAndRankPatterns(
    patterns,
    { query = '', favoriteIds = [], recentIds = [] } = {},
) {
    const needle = normalize(query);
    const favorites = new Map(favoriteIds.map((id, index) => [id, index]));
    const recents = new Map(recentIds.map((id, index) => [id, index]));

    return patterns
        .filter((pattern) => (
            !needle
            || normalize(pattern.id).includes(needle)
            || normalize(pattern.name).includes(needle)
        ))
        .map((pattern, sourceIndex) => ({ pattern, sourceIndex }))
        .sort((left, right) => {
            const leftFavorite = favorites.get(left.pattern.id) ?? Number.MAX_SAFE_INTEGER;
            const rightFavorite = favorites.get(right.pattern.id) ?? Number.MAX_SAFE_INTEGER;
            if (leftFavorite !== rightFavorite) return leftFavorite - rightFavorite;

            const leftRecent = recents.get(left.pattern.id) ?? Number.MAX_SAFE_INTEGER;
            const rightRecent = recents.get(right.pattern.id) ?? Number.MAX_SAFE_INTEGER;
            if (leftRecent !== rightRecent) return leftRecent - rightRecent;

            return left.sourceIndex - right.sourceIndex;
        })
        .map(({ pattern }) => pattern);
}

export function updateRecentIds(ids, selectedId, limit = 8) {
    const uniqueIds = [...new Set(ids.filter((id) => typeof id === 'string'))];
    if (!selectedId) return uniqueIds.slice(0, limit);
    return [selectedId, ...uniqueIds.filter((id) => id !== selectedId)].slice(0, limit);
}
