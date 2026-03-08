import { useState, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import type { AudioSource, AudioLevels } from '../types';

export interface UseAudioSourcesReturn {
  audioSources: AudioSource[];
  selectedSource: string | null;
  setSelectedSource: (source: string | null) => void;
  isTestingAudio: boolean;
  loadAudioSources: () => Promise<void>;
  handleSourceChange: (sourceId: string | null, isConnected: boolean) => Promise<void>;
  handleStartTest: () => Promise<void>;
  handleStopTest: () => Promise<void>;
}

export function useAudioSources(): UseAudioSourcesReturn {
  const [audioSources, setAudioSources] = useState<AudioSource[]>([]);
  const [selectedSource, setSelectedSource] = useState<string | null>(null);
  const [isTestingAudio, setIsTestingAudio] = useState(false);
  const [_testBands, setTestBands] = useState<number[]>([0, 0, 0, 0, 0]);
  const testIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const testTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const loadAudioSources = async () => {
    try {
      const sources = await invoke<AudioSource[]>('list_audio_sources');
      setAudioSources(sources);
      if (sources.length === 0) {
        setSelectedSource(null);
        return;
      }

      setSelectedSource((currentSelected) => {
        const selectedStillExists = currentSelected
          ? sources.some((source) => source.id === currentSelected)
          : false;
        if (selectedStillExists) {
          return currentSelected;
        }

        const savedSourceId = localStorage.getItem('mcav.audioSource');
        const savedSourceExists = savedSourceId
          ? sources.some((source) => source.id === savedSourceId)
          : false;
        return savedSourceExists ? savedSourceId : sources[0].id;
      });
    } catch (e) {
      console.error('Failed to load audio sources:', e);
    }
  };

  const handleSourceChange = async (sourceId: string | null, isConnected: boolean) => {
    setSelectedSource(sourceId);
    if (sourceId) {
      localStorage.setItem('mcav.audioSource', sourceId);
    }
    if (sourceId && isConnected) {
      try {
        await invoke('change_audio_source', { sourceId });
      } catch (e) {
        console.error('Failed to change audio source:', e);
      }
    }
  };

  const handleStopTest = async () => {
    if (testIntervalRef.current) {
      clearInterval(testIntervalRef.current);
      testIntervalRef.current = null;
    }
    if (testTimeoutRef.current) {
      clearTimeout(testTimeoutRef.current);
      testTimeoutRef.current = null;
    }
    try {
      await invoke('stop_capture');
      setIsTestingAudio(false);
      setTestBands([0, 0, 0, 0, 0]);
    } catch (err) {
      console.error('Failed to stop test audio:', err);
    }
  };

  const handleStartTest = async () => {
    if (!selectedSource) return;

    try {
      setIsTestingAudio(true);
      await invoke('start_capture', { sourceId: selectedSource });

      // Poll audio levels for 10 seconds
      testIntervalRef.current = setInterval(async () => {
        try {
          const levels = await invoke<AudioLevels>('get_audio_levels');
          setTestBands(levels.bands);
        } catch (err) {
          console.error('Failed to get audio levels:', err);
        }
      }, 50);

      // Auto-stop after 10 seconds
      testTimeoutRef.current = setTimeout(() => {
        if (testIntervalRef.current) clearInterval(testIntervalRef.current);
        testIntervalRef.current = null;
        testTimeoutRef.current = null;
        void handleStopTest();
      }, 10000);
    } catch (err) {
      console.error('Failed to start test audio:', err);
      setIsTestingAudio(false);
    }
  };

  return {
    audioSources,
    selectedSource,
    setSelectedSource,
    isTestingAudio,
    loadAudioSources,
    handleSourceChange,
    handleStartTest,
    handleStopTest,
  };
}
