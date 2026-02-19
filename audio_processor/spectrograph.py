"""
Terminal-based spectrograph display for audio visualization.
Enhanced TUI with colors, beat indicators, and status display.
"""

import os
import shutil
import sys


def is_vscode_terminal() -> bool:
    """Check if running in VS Code's integrated terminal."""
    return os.environ.get("TERM_PROGRAM") == "vscode"


class Colors:
    """ANSI color codes for terminal output."""

    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"

    # Foreground colors
    BLACK = "\033[30m"
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    BLUE = "\033[34m"
    MAGENTA = "\033[35m"
    CYAN = "\033[36m"
    WHITE = "\033[37m"

    # Bright foreground
    BRIGHT_RED = "\033[91m"
    BRIGHT_GREEN = "\033[92m"
    BRIGHT_YELLOW = "\033[93m"
    BRIGHT_BLUE = "\033[94m"
    BRIGHT_MAGENTA = "\033[95m"
    BRIGHT_CYAN = "\033[96m"

    # Background colors
    BG_RED = "\033[41m"
    BG_GREEN = "\033[42m"
    BG_YELLOW = "\033[43m"
    BG_BLUE = "\033[44m"
    BG_MAGENTA = "\033[45m"
    BG_CYAN = "\033[46m"

    # Cursor control
    HIDE_CURSOR = "\033[?25l"
    SHOW_CURSOR = "\033[?25h"
    CLEAR_LINE = "\033[2K"
    CLEAR_SCREEN = "\033[2J"
    HOME = "\033[H"
    SAVE_CURSOR = "\033[s"
    RESTORE_CURSOR = "\033[u"
    MOVE_UP = "\033[{}A"
    MOVE_TO_COL = "\033[{}G"


# Band colors (matching the web UI) - 5 bands for ultra-low-latency mode
BAND_COLORS = [
    Colors.YELLOW,  # Bass (includes kick drums)
    Colors.BRIGHT_YELLOW,  # Low-mid
    Colors.BRIGHT_GREEN,  # Mid
    Colors.BRIGHT_BLUE,  # High-mid
    Colors.BRIGHT_MAGENTA,  # High/Air
]

BAND_NAMES = ["Bass", "Low ", "Mid ", "High", "Air "]


class TerminalSpectrograph:
    """Enhanced terminal spectrograph with colors and better layout."""

    def __init__(self, vscode_mode: bool = None):
        self._frame = 0
        self._last_beat = -10
        self._beat_intensity = 0.0
        self._initialized = False
        self._lines_used = 0

        # Stats tracking
        self._preset = "auto"
        self._estimated_bpm = 120
        self._music_variance = 0.5
        self._attack = 0.35
        self._release = 0.08
        self._beat_threshold = 1.3
        self._browser_clients = 0
        self._using_fft = False
        self._latency_ms = 0.0

        # VS Code compatibility - auto-detect or use provided value
        self._vscode_mode = vscode_mode if vscode_mode is not None else is_vscode_terminal()

        # Get terminal width
        try:
            self._width = shutil.get_terminal_size().columns
        except OSError:
            self._width = 80

        self._bar_width = min(30, self._width - 20)

        # Enable ANSI on Windows
        if sys.platform == "win32":
            os.system("")  # Enables ANSI escape sequences

        # For VS Code: clear screen once at start and use home positioning
        if self._vscode_mode:
            sys.stdout.write(Colors.CLEAR_SCREEN)
            sys.stdout.write(Colors.HOME)
            sys.stdout.flush()

    def set_stats(
        self,
        preset=None,
        bpm=None,
        variance=None,
        attack=None,
        release=None,
        threshold=None,
        clients=None,
        using_fft=None,
        latency_ms=None,
    ):
        """Update display stats from the agent."""
        if preset is not None:
            self._preset = preset
        if bpm is not None:
            self._estimated_bpm = bpm
        if variance is not None:
            self._music_variance = variance
        if attack is not None:
            self._attack = attack
        if release is not None:
            self._release = release
        if threshold is not None:
            self._beat_threshold = threshold
        if clients is not None:
            self._browser_clients = clients
        if using_fft is not None:
            self._using_fft = using_fft
        if latency_ms is not None:
            self._latency_ms = latency_ms

    def _make_bar(self, value: float, width: int, color: str) -> str:
        """Create a colored progress bar."""
        value = max(0, min(1, value))
        filled = int(value * width)
        empty = width - filled

        # Use block characters for smooth fill
        bar = "█" * filled + "░" * empty
        return f"{color}{bar}{Colors.RESET}"

    def _beat_indicator(self, is_beat: bool, intensity: float) -> str:
        """Create beat indicator with flash effect."""
        frames_since_beat = self._frame - self._last_beat

        if frames_since_beat < 2:
            # Fresh beat - bright flash
            return f"{Colors.BG_RED}{Colors.WHITE}{Colors.BOLD} BEAT {Colors.RESET}"
        elif frames_since_beat < 5:
            # Fading
            return f"{Colors.RED}{Colors.BOLD}[BEAT]{Colors.RESET}"
        elif frames_since_beat < 10:
            return f"{Colors.DIM}[beat]{Colors.RESET}"
        else:
            return f"{Colors.DIM}[    ]{Colors.RESET}"

    def display(self, bands, amplitude=0.0, is_beat=False, beat_intensity=0.0):
        """Display enhanced spectrograph."""
        self._frame += 1

        if is_beat:
            self._last_beat = self._frame
            self._beat_intensity = beat_intensity

        # Position cursor for redraw
        if self._vscode_mode:
            # VS Code: always go to home position
            sys.stdout.write(Colors.HOME)
        elif self._initialized:
            # Normal terminal: move cursor up
            sys.stdout.write(f"\033[{self._lines_used}A")

        lines = []

        # === HEADER ===
        header = f"{Colors.CYAN}{Colors.BOLD}# AudioViz{Colors.RESET}"
        preset_color = {
            "auto": Colors.CYAN,
            "edm": Colors.BRIGHT_RED,
            "chill": Colors.BRIGHT_MAGENTA,
            "rock": Colors.YELLOW,
        }.get(self._preset, Colors.WHITE)
        preset_str = f"{preset_color}[{self._preset.upper()}]{Colors.RESET}"

        # FFT indicator
        if self._using_fft:
            fft_str = f" {Colors.BRIGHT_GREEN}FFT{Colors.RESET}"
        else:
            fft_str = f" {Colors.DIM}SYN{Colors.RESET}"

        clients_str = ""
        if self._browser_clients > 0:
            clients_str = f" {Colors.GREEN}●{Colors.RESET} {self._browser_clients} client{'s' if self._browser_clients != 1 else ''}"

        lines.append(f"{header} {preset_str}{fft_str}{clients_str}")
        lines.append(f"{Colors.DIM}{'─' * min(50, self._width - 2)}{Colors.RESET}")

        # === FREQUENCY BANDS ===
        for i, (band_val, name, color) in enumerate(zip(bands, BAND_NAMES, BAND_COLORS)):
            # Protect against NaN values
            if not isinstance(band_val, (int, float)) or not (-1e10 < band_val < 1e10):
                band_val = 0.0
            band_val = max(0.0, min(1.0, float(band_val)))
            bar = self._make_bar(band_val, self._bar_width, color)
            pct = int(band_val * 100)
            lines.append(f"{color}{name}{Colors.RESET} {bar} {pct:3d}%")

        # === BEAT & VOLUME ROW ===
        beat_ind = self._beat_indicator(is_beat, beat_intensity)
        # Protect against NaN
        if not isinstance(amplitude, (int, float)) or not (-1e10 < amplitude < 1e10):
            amplitude = 0.0
        amplitude = max(0.0, min(1.0, float(amplitude)))
        vol_bar = self._make_bar(amplitude, 15, Colors.WHITE)
        lines.append(f"{Colors.DIM}{'─' * min(50, self._width - 2)}{Colors.RESET}")
        lines.append(f"Vol  {vol_bar} {int(amplitude * 100):3d}%  {beat_ind}")

        # === STATS ROW ===
        latency_str = f"lat={self._latency_ms:.0f}ms" if self._latency_ms > 0 else ""
        if self._preset == "auto":
            # Show auto-calibration info
            bpm_str = f"BPM≈{self._estimated_bpm:.0f}"
            var_str = f"var={self._music_variance:.2f}"
            stats = f"{Colors.DIM}{bpm_str} {var_str} atk={self._attack:.2f} thr={self._beat_threshold:.2f} {latency_str}{Colors.RESET}"
        else:
            stats = f"{Colors.DIM}atk={self._attack:.2f} rel={self._release:.2f} thr={self._beat_threshold:.2f} {latency_str}{Colors.RESET}"

        lines.append(stats)

        # === FRAME COUNTER ===
        lines.append(f"{Colors.DIM}Frame: {self._frame}{Colors.RESET}")

        # Print all lines
        for line in lines:
            sys.stdout.write(f"{Colors.CLEAR_LINE}{line}\n")

        sys.stdout.flush()
        self._lines_used = len(lines)
        self._initialized = True

    def clear(self):
        """Clean up display."""
        if self._vscode_mode:
            # VS Code: clear screen and go home
            sys.stdout.write(Colors.CLEAR_SCREEN)
            sys.stdout.write(Colors.HOME)
        elif self._initialized:
            # Normal terminal: clear the lines we used
            for _ in range(self._lines_used):
                sys.stdout.write(f"{Colors.CLEAR_LINE}\n")
            sys.stdout.write(f"\033[{self._lines_used}A")

        sys.stdout.write(Colors.SHOW_CURSOR)
        sys.stdout.flush()
        print()


class CompactSpectrograph:
    """Minimal single-line spectrograph for simple output."""

    def __init__(self):
        self._frame = 0
        self._last_beat = -10

    def display(self, bands, amplitude=0.0, is_beat=False, beat_intensity=0.0):
        """Display compact spectrograph on single line."""
        self._frame += 1

        if is_beat:
            self._last_beat = self._frame

        def bar(val):
            val = max(0, min(1, val))
            chars = " ▁▂▃▄▅▆▇█"
            idx = int(val * (len(chars) - 1))
            return chars[idx]

        band_str = "".join(bar(b) for b in bands)
        vol = int(amplitude * 100)
        beat_char = "!" if (self._frame - self._last_beat) < 3 else " "

        line = f"\r[{band_str}] Vol:{vol:3d}% {beat_char} Frame:{self._frame}    "
        sys.stdout.write(line)
        sys.stdout.flush()

    def set_stats(self, **kwargs):
        """No-op for compact display."""
        pass

    def clear(self):
        print()
