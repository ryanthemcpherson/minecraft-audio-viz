'use client';

import { useEffect, useState, useRef } from 'react';
import Image from 'next/image';
import Link from 'next/link';

interface DJProfile {
  dj_id: string;
  dj_name: string;
  avatar_url: string | null;
  color_palette: string[] | null;
  slug: string | null;
  genres: string | null;
}

interface NowPlayingProps {
  wsUrl: string;
}

export default function NowPlaying({ wsUrl }: NowPlayingProps) {
  const [activeDj, setActiveDj] = useState<DJProfile | null>(null);
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!wsUrl) return;

    const connect = () => {
      try {
        const ws = new WebSocket(wsUrl);
        wsRef.current = ws;

        ws.onopen = () => setConnected(true);
        ws.onclose = () => {
          setConnected(false);
          setActiveDj(null);
          reconnectRef.current = setTimeout(connect, 5000);
        };
        ws.onerror = () => ws.close();

        ws.onmessage = (event) => {
          try {
            const msg = JSON.parse(event.data);
            if (msg.type === 'state' && msg.active_dj !== undefined) {
              setActiveDj(msg.active_dj);
            }
          } catch {
            // Ignore parse errors
          }
        };
      } catch {
        reconnectRef.current = setTimeout(connect, 5000);
      }
    };

    connect();
    return () => {
      if (reconnectRef.current) clearTimeout(reconnectRef.current);
      wsRef.current?.close();
    };
  }, [wsUrl]);

  if (!connected || !activeDj) return null;

  const primaryColor = activeDj.color_palette?.[0] ?? '#00CCFF';

  return (
    <div className="fixed bottom-6 right-6 z-50 flex items-center gap-3 rounded-xl border border-white/[0.06] bg-[#08090d]/80 px-4 py-3 backdrop-blur-xl">
      <div
        className="h-2 w-2 animate-pulse rounded-full"
        style={{ background: primaryColor }}
      />
      {activeDj.avatar_url ? (
        <Image
          src={activeDj.avatar_url}
          alt={activeDj.dj_name}
          width={32}
          height={32}
          className="rounded-full object-cover"
          unoptimized
        />
      ) : (
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-white/[0.08] text-sm font-semibold text-[#a1a1aa]">
          {activeDj.dj_name.charAt(0).toUpperCase()}
        </div>
      )}
      <div>
        <div className="text-sm font-medium text-[#f5f5f5]">
          {activeDj.slug ? (
            <Link href={`/dj/${activeDj.slug}`} className="hover:underline">
              {activeDj.dj_name}
            </Link>
          ) : (
            activeDj.dj_name
          )}
        </div>
        {activeDj.genres && (
          <div className="text-xs text-[#a1a1aa]">{activeDj.genres}</div>
        )}
      </div>
    </div>
  );
}
