"""Allow running the Discord bot as ``python -m discord_bot``."""

import asyncio

from discord_bot.bot import main

asyncio.run(main())
