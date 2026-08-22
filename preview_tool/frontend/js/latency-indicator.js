(function () {
    const dot = document.getElementById('latency-dot');
    const display = document.getElementById('latency-display');
    if (!dot || !display) return;

    const observer = new MutationObserver(function () {
        const match = display.textContent.match(/([\d.]+)\s*ms/);
        if (!match) return;

        const latencyMilliseconds = parseFloat(match[1]);
        dot.classList.remove('latency-good', 'latency-warn', 'latency-bad');
        if (latencyMilliseconds < 20) dot.classList.add('latency-good');
        else if (latencyMilliseconds <= 50) dot.classList.add('latency-warn');
        else dot.classList.add('latency-bad');
    });
    observer.observe(display, { childList: true, characterData: true, subtree: true });
})();
