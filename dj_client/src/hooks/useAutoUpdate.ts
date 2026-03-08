import { useState, useEffect } from 'react';
import { check, type Update } from '@tauri-apps/plugin-updater';

export interface UseAutoUpdateReturn {
  availableUpdate: Update | null;
  isInstallingUpdate: boolean;
  dismissUpdateBanner: boolean;
  setDismissUpdateBanner: (value: boolean) => void;
  installAvailableUpdate: () => Promise<void>;
}

export function useAutoUpdate(): UseAutoUpdateReturn {
  const [availableUpdate, setAvailableUpdate] = useState<Update | null>(null);
  const [_isCheckingUpdate, setIsCheckingUpdate] = useState(false);
  const [isInstallingUpdate, setIsInstallingUpdate] = useState(false);
  const [_updateMessage, setUpdateMessage] = useState<string | null>(null);
  const [_updateError, setUpdateError] = useState<string | null>(null);
  const [_updateProgress, setUpdateProgress] = useState<number | null>(null);
  const [dismissUpdateBanner, setDismissUpdateBanner] = useState(false);

  const checkForUpdates = async (manual: boolean) => {
    setIsCheckingUpdate(true);
    if (manual) {
      setUpdateError(null);
    }
    if (manual) {
      setUpdateMessage('Checking for updates...');
    }

    try {
      const update = await check();
      setAvailableUpdate(update);
      setUpdateProgress(null);

      if (update) {
        setDismissUpdateBanner(false);
        setUpdateMessage(`Update ${update.version} is available.`);
      } else if (manual) {
        setUpdateMessage('You are on the latest version.');
      } else {
        setUpdateMessage(null);
      }
    } catch (e) {
      const errStr = String(e);
      const isAcl = errStr.includes('not allowed by ACL');
      console.error(`[updater] Update check failed: ${errStr}`);
      if (manual) {
        setUpdateError(
          isAcl ? 'Updates are not available in this build.' : `Update check failed: ${errStr}`,
        );
      } else {
        setUpdateMessage(null);
      }
    } finally {
      setIsCheckingUpdate(false);
    }
  };

  // Check for updates on mount
  useEffect(() => {
    checkForUpdates(false);
  }, []);

  // Check for updates every 6 hours
  useEffect(() => {
    const interval = setInterval(() => {
      void checkForUpdates(false);
    }, 1000 * 60 * 60 * 6);

    return () => clearInterval(interval);
  }, []);

  const installAvailableUpdate = async () => {
    if (!availableUpdate) return;

    setIsInstallingUpdate(true);
    setUpdateError(null);
    setUpdateProgress(0);
    setUpdateMessage(`Downloading v${availableUpdate.version}...`);

    let downloadedBytes = 0;
    let totalBytes = 0;
    try {
      await availableUpdate.downloadAndInstall((event) => {
        if (event.event === 'Started') {
          totalBytes = event.data.contentLength ?? 0;
          downloadedBytes = 0;
          setUpdateProgress(0);
          return;
        }

        if (event.event === 'Progress') {
          downloadedBytes += event.data.chunkLength;
          if (totalBytes > 0) {
            const pct = Math.min(100, Math.round((downloadedBytes / totalBytes) * 100));
            setUpdateProgress(pct);
          } else {
            setUpdateProgress(null);
          }
          return;
        }

        if (event.event === 'Finished') {
          setUpdateProgress(100);
        }
      });

      setAvailableUpdate(null);
      setUpdateMessage('Update installed. Please restart the DJ app.');
      setDismissUpdateBanner(false);
    } catch (e) {
      setUpdateError(`Update install failed: ${String(e)}`);
    } finally {
      setIsInstallingUpdate(false);
    }
  };

  return {
    availableUpdate,
    isInstallingUpdate,
    dismissUpdateBanner,
    setDismissUpdateBanner,
    installAvailableUpdate,
  };
}
