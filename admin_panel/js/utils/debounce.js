/**
 * Debounce utility - prevents excessive function calls
 */

/**
 * Creates a debounced version of a function that delays execution
 * until after `delay` milliseconds have elapsed since the last call.
 *
 * @param {Function} fn - The function to debounce
 * @param {number} delay - Delay in milliseconds
 * @returns {Function} Debounced function
 */
export function debounce(fn, delay) {
    let timeoutId = null;

    const debounced = function(...args) {
        if (timeoutId !== null) {
            clearTimeout(timeoutId);
        }
        timeoutId = setTimeout(() => {
            fn.apply(this, args);
            timeoutId = null;
        }, delay);
    };

    debounced.cancel = function() {
        if (timeoutId !== null) {
            clearTimeout(timeoutId);
            timeoutId = null;
        }
    };

    debounced.flush = function(...args) {
        debounced.cancel();
        fn.apply(this, args);
    };

    return debounced;
}

/**
 * Creates a throttled version of a function that only executes
 * at most once per `limit` milliseconds.
 *
 * @param {Function} fn - The function to throttle
 * @param {number} limit - Minimum time between calls in milliseconds
 * @returns {Function} Throttled function
 */
export function throttle(fn, limit) {
    let lastCall = 0;
    let timeoutId = null;

    return function(...args) {
        const now = Date.now();
        const remaining = limit - (now - lastCall);

        if (remaining <= 0) {
            if (timeoutId !== null) {
                clearTimeout(timeoutId);
                timeoutId = null;
            }
            lastCall = now;
            fn.apply(this, args);
        } else if (timeoutId === null) {
            timeoutId = setTimeout(() => {
                lastCall = Date.now();
                timeoutId = null;
                fn.apply(this, args);
            }, remaining);
        }
    };
}

/**
 * Rate limiter for high-frequency updates (like audio meters).
 * Uses requestAnimationFrame for smooth visuals.
 *
 * @param {Function} fn - The function to rate limit
 * @returns {Function} Rate-limited function
 */
export function rafThrottle(fn) {
    let rafId = null;
    let lastArgs = null;

    return function(...args) {
        lastArgs = args;

        if (rafId === null) {
            rafId = requestAnimationFrame(() => {
                fn.apply(this, lastArgs);
                rafId = null;
            });
        }
    };
}
