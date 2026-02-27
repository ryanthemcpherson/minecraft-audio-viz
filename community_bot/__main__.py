"""Entry point: python -m community_bot"""

from __future__ import annotations

import asyncio
import logging

from aiohttp import web

from community_bot.config import Config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
log = logging.getLogger("community_bot")


async def main() -> None:
    config = Config.from_env()
    log.info("Starting MCAV Community Bot (guild=%s)", config.guild_id)

    from community_bot.bot import CommunityBot
    from community_bot.webhook_server import create_webhook_app

    bot = CommunityBot(config)

    # Start webhook server
    webhook_app = create_webhook_app(bot)
    runner = web.AppRunner(webhook_app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", config.webhook_port)
    await site.start()
    log.info("Webhook server listening on port %s", config.webhook_port)

    # Start Discord bot (blocks until disconnect)
    try:
        await bot.start(config.bot_token)
    finally:
        await runner.cleanup()
        await bot.close()


def main_sync() -> None:
    asyncio.run(main())


if __name__ == "__main__":
    main_sync()
